package com.tom.cpm.blockbench.tools.particles;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import com.tom.cpm.blockbench.convert.ProjectConvert;
import com.tom.cpm.blockbench.format.ProjectData;
import com.tom.cpm.blockbench.proxy.Project;
import com.tom.cpm.blockbench.proxy.Texture;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.shared.definition.ModelDefinitionLoader;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.Exporter;
import com.tom.cpm.shared.editor.anim.EditorAnim;
import com.tom.cpm.shared.editor.elements.ElementType;
import com.tom.cpm.shared.editor.util.ExportHelper;
import com.tom.cpm.shared.editor.util.GetFreeSkinSlots;
import com.tom.cpm.shared.io.ChecksumOutputStream;
import com.tom.cpm.shared.io.IOHelper;
import com.tom.cpm.shared.model.RootModelType;
import com.tom.cpm.shared.parts.PartCollection;

import elemental2.promise.Promise;

/**
 * CPM+ (W6-PARTICLES): the always-visible counter of the particle dialog. ADVISORY ONLY (Convention 13): cube count
 * 256, link 100 KB and texture 256 px are limits of OTHER players' safety settings; they are shown per audience and
 * never block, clamp or auto-reduce anything. The model is measured the way CPM writes it (headless conversion,
 * Exporter.prepareExport + writePackage); the particles are added with {@link ParticleMath#estimateAddedBytes}.
 */
public final class ParticleBudget {
	/** project-level 'Público do modelo' choice (G16 extra): "friends" = friends / safety off through a gist link */
	public static final String AUDIENCE_KEY = "cpmplus_audience";

	private ParticleBudget() {
	}

	/** the model as it is now */
	public static final class Measure {
		public int cubes = -1;
		public int rawBytes = -1;
		public int maxTexture;
		public String error;
	}

	/** before -&gt; after numbers for a set of parameters */
	public static final class Estimate {
		public int cubesBefore = -1, cubesAfter = -1;
		public int rawBefore = -1, rawAfter = -1;
		public int addedBytes;
		public int maxTexture;
		public boolean mediumBefore, mediumAfter, linkBefore, linkAfter, friendsAfter;
	}

	/** measures the current project (async; never changes it) */
	public static Promise<Measure> measure() {
		Measure m = new Measure();
		try {
			for (Texture t : Texture.all.array())m.maxTexture = Math.max(m.maxTexture, Math.max(ParticleTexPacker.imgWidth(t), ParticleTexPacker.imgHeight(t)));
		} catch (Throwable e) {
		}
		return ProjectConvert.buildEditor(false).then(e -> {
			try {
				m.cubes = inGameCubes(e);
			} catch (Throwable ex) {
				m.error = String.valueOf(ex);
			}
			try {
				m.rawBytes = packageSize(e);
			} catch (Throwable ex) {
				if (m.error == null)m.error = String.valueOf(ex);
			}
			return Promise.resolve(m);
		}).catch_(err -> {
			m.error = String.valueOf(err);
			return Promise.resolve(m);
		});
	}

	/**
	 * Cubes the game counts against MAX_CUBE_COUNT, the way CPM flattens the model (ExportHelper.flattenElements): one
	 * per normal element (templates excluded) + one per duplicated root / extra root type (cape, elytra, armor...).
	 */
	static int inGameCubes(Editor e) {
		int[] c = new int[1];
		ExportHelper.walkElements(e.elements, me -> {
			if (me.templateElement)return;
			if (me.type == ElementType.NORMAL)c[0]++;
			else if (me.type == ElementType.ROOT_PART && (me.duplicated || me.typeData instanceof RootModelType))c[0]++;
		});
		return c[0];
	}

	/** size of the model data CPM writes (what goes into the overflow link when it does not fit inline) */
	static int packageSize(Editor e) throws Exception {
		if (e.animEnc == null && e.animations.stream().anyMatch(EditorAnim::isCustom)) {
			try {
				e.animEnc = GetFreeSkinSlots.getDefault(e);
			} catch (Throwable ex) {
			}
		}
		PartCollection def = Exporter.prepareExport(e);
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		baos.write(ModelDefinitionLoader.HEADER);
		ChecksumOutputStream cos = new ChecksumOutputStream(baos);
		def.writePackage(new IOHelper(cos));
		cos.close();
		return baos.size();
	}

	public static Estimate estimate(Measure m, int count, int frames, boolean color, int growH) {
		Estimate s = new Estimate();
		s.maxTexture = m != null ? m.maxTexture : 0;
		if (growH > 1)s.maxTexture = Math.max(s.maxTexture, s.maxTexture * growH);
		s.addedBytes = ParticleMath.estimateAddedBytes(count, frames, color, 1);
		if (m != null && m.cubes >= 0) {
			s.cubesBefore = m.cubes;
			s.cubesAfter = m.cubes + count + 1;
		}
		if (m != null && m.rawBytes >= 0) {
			s.rawBefore = m.rawBytes;
			s.rawAfter = m.rawBytes + s.addedBytes;
			s.linkBefore = ParticleMath.needsLink(s.rawBefore);
			s.linkAfter = ParticleMath.needsLink(s.rawAfter);
		}
		int cb = Math.max(0, s.cubesBefore), ca = Math.max(0, s.cubesAfter);
		s.mediumBefore = ParticleMath.mediumLoads(cb, Math.max(0, s.rawBefore), m != null ? m.maxTexture : 0);
		s.mediumAfter = ParticleMath.mediumLoads(ca, Math.max(0, s.rawAfter), s.maxTexture);
		s.friendsAfter = ca <= 32767 && (!s.linkAfter || ParticleMath.downloadKB(Math.max(0, s.rawAfter)) <= 1024);
		return s;
	}

	/** true when the project chose 'amigos ou segurança desligada (link gist)' */
	public static boolean friendsAudience() {
		try {
			ProjectData pd = Project.getData();
			Object v = pd.getExtra(AUDIENCE_KEY);
			return v instanceof String && ((String) v).startsWith("friend");
		} catch (Throwable e) {
			return false;
		}
	}

	/** the counter lines (plain text, pt-BR first) */
	public static List<String> lines(Estimate s, Measure m, int count) {
		List<String> out = new ArrayList<>();
		if (s.cubesBefore >= 0)out.add(PlusDialogs.trf("cpmplus.w6particles.budget.parts", s.cubesBefore, s.cubesAfter, count));
		else out.add(PlusDialogs.trf("cpmplus.w6particles.budget.partsAdd", count + 1));
		if (s.rawBefore >= 0) {
			out.add(PlusDialogs.trf("cpmplus.w6particles.budget.size", kb(s.rawBefore), kb(s.rawAfter)));
			if (s.linkAfter && !s.linkBefore)out.add(PlusDialogs.tr("cpmplus.w6particles.budget.linkNew"));
			else if (s.linkAfter)out.add(PlusDialogs.trf("cpmplus.w6particles.budget.link", ParticleMath.downloadKB(s.rawAfter)));
		} else if (m != null && m.error != null) {
			out.add(PlusDialogs.trf("cpmplus.w6particles.budget.unknown", m.error));
		}
		if (friendsAudience()) {
			out.add(PlusDialogs.tr("cpmplus.w6particles.budget.friendsQuiet"));
			return out;
		}
		String medium;
		if (s.mediumAfter)medium = PlusDialogs.tr("cpmplus.w6particles.budget.ok");
		else {
			List<String> why = new ArrayList<>();
			if (s.cubesAfter > ParticleMath.MEDIUM_CUBES)why.add(PlusDialogs.trf("cpmplus.w6particles.budget.whyCubes", s.cubesAfter));
			if (s.linkAfter && ParticleMath.downloadKB(s.rawAfter) > ParticleMath.MEDIUM_LINK_KB)why.add(PlusDialogs.trf("cpmplus.w6particles.budget.whyLink", ParticleMath.downloadKB(s.rawAfter)));
			if (s.maxTexture > ParticleMath.MEDIUM_TEXTURE)why.add(PlusDialogs.trf("cpmplus.w6particles.budget.whyTex", s.maxTexture));
			medium = PlusDialogs.trf("cpmplus.w6particles.budget.no", join(why));
		}
		out.add(PlusDialogs.trf("cpmplus.w6particles.budget.who", s.friendsAfter ? PlusDialogs.tr("cpmplus.w6particles.budget.ok") : PlusDialogs.tr("cpmplus.w6particles.budget.noShort"), medium));
		if (!s.mediumBefore)out.add(PlusDialogs.tr("cpmplus.w6particles.budget.already"));
		return out;
	}

	private static String join(List<String> l) {
		StringBuilder sb = new StringBuilder();
		for (String s : l) {
			if (sb.length() > 0)sb.append(", ");
			sb.append(s);
		}
		return sb.toString();
	}

	static String kb(int bytes) {
		double k = bytes / 1024.0;
		long t = Math.round(k * 10);
		return (t / 10) + "," + (t % 10);
	}
}
