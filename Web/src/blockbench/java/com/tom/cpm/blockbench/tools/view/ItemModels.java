package com.tom.cpm.blockbench.tools.view;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.tom.cpl.math.MatrixStack;
import com.tom.cpl.math.Vec3f;
import com.tom.cpl.render.VertexBuffer;
import com.tom.cpl.util.ItemSlot;
import com.tom.cpm.shared.editor.DisplayItem;
import com.tom.cpm.shared.model.builtin.BlockModel;
import com.tom.cpm.shared.model.builtin.IItemModel;
import com.tom.cpm.shared.model.builtin.IItemModel.ItemRenderTransform;
import com.tom.cpm.shared.model.builtin.ItemModel;
import com.tom.cpm.shared.model.builtin.ShieldModel;
import com.tom.cpm.shared.model.builtin.SkullModel;
import com.tom.cpm.shared.model.builtin.SpyglassModel;
import com.tom.cpm.shared.model.builtin.TridentModel;

/**
 * CPM+ (W3-COPYXF, G66): the held-item models of CPM's editor (ViewportPanelBase3d), as plain vertex arrays for a
 * Blockbench preview mesh. The geometry is produced by CPM's OWN model classes (ItemModel = BoxRender's extruded
 * sprite, ShieldModel, TridentModel, SpyglassModel, SkullModel, BlockModel) rendered with their per-hand item
 * transform into a capturing VertexBuffer, so it is exactly what CPM's editor draws in the slot's frame; only the
 * axis change to Blockbench is applied here: CPM model space (1 = one block, Y down) -> Blockbench pixels
 * (x' = -16x, y' = -16y, z' = 16z, a 180 degree turn around Z, so the winding stays the same).
 * <p>
 * The transform tables are the ones of ViewportPanelBase3d (copied: they are protected there). Results are cached per
 * (item, state, hand); nothing here touches the project.
 */
public final class ItemModels {
	private static final ItemRenderTransform[] SWORD = {
			new ItemRenderTransform(new Vec3f(0, 4, 0.5F), new Vec3f(0, -90, 55), new Vec3f(0.85F, 0.85F, 0.85F)),
			new ItemRenderTransform(new Vec3f(0, 4, 0.5F), new Vec3f(0, 90, -55), new Vec3f(0.85F, 0.85F, 0.85F))
	};
	private static final ItemRenderTransform[] BOW = {
			new ItemRenderTransform(new Vec3f(-1, -2, 2.5F), new Vec3f(-80, 260, -40), new Vec3f(0.9F, 0.9F, 0.9F)),
			new ItemRenderTransform(new Vec3f(-1, -2, 2.5F), new Vec3f(-80, -280, 40), new Vec3f(0.9F, 0.9F, 0.9F))
	};
	private static final ItemRenderTransform[] CROSSBOW = {
			new ItemRenderTransform(new Vec3f(2, 0.1F, -3), new Vec3f(-90, 0, -60), new Vec3f(0.9F, 0.9F, 0.9F)),
			new ItemRenderTransform(new Vec3f(2, 0.1F, -3), new Vec3f(-90, 0,  30), new Vec3f(0.9F, 0.9F, 0.9F))
	};
	private static final ItemRenderTransform[] ITEM = {
			new ItemRenderTransform(new Vec3f(0, 3, 1), new Vec3f(0, 0, 0), new Vec3f(0.55F, 0.55F, 0.55F))
	};
	private static final ItemRenderTransform[] GOAT_HORN = {
			new ItemRenderTransform(new Vec3f(-1, 2, 2), new Vec3f(0, -125, 0), new Vec3f(0.5F, 0.5F, 0.5F)),
			new ItemRenderTransform(new Vec3f( 1, 2, 2), new Vec3f(0,  125, 0), new Vec3f(0.5F, 0.5F, 0.5F))
	};
	private static final ItemRenderTransform[] BRUSH = {
			new ItemRenderTransform(new Vec3f(0, 4, 0), new Vec3f(0, 0,  45), new Vec3f(0.9F, 0.9F, 0.9F)),
			new ItemRenderTransform(new Vec3f(0, 4, 0), new Vec3f(0, 0, -45), new Vec3f(0.9F, 0.9F, 0.9F))
	};
	private static final ItemRenderTransform[] SPEAR = {
			new ItemRenderTransform(new Vec3f(0, 2, 2), new Vec3f(5,  270, -50), new Vec3f(1.7F, 1.7F, 0.85F)),
			new ItemRenderTransform(new Vec3f(0, 2, 2), new Vec3f(5, -270,  50), new Vec3f(1.7F, 1.7F, 0.85F))
	};

	private static Map<DisplayItem, IItemModel[]> models;
	private static final Map<String, Geo> CACHE = new HashMap<>();

	private ItemModels() {
	}

	private static Map<DisplayItem, IItemModel[]> models() {
		if (models == null) {
			Map<DisplayItem, IItemModel[]> m = new HashMap<>();
			m.put(DisplayItem.BLOCK, new IItemModel[] {new BlockModel()});
			m.put(DisplayItem.SHIELD, new IItemModel[] {new ShieldModel()});
			m.put(DisplayItem.TRIDENT, new IItemModel[] {new TridentModel()});
			m.put(DisplayItem.SKULL, new IItemModel[] {new SkullModel()});
			m.put(DisplayItem.SWORD, new IItemModel[] {new ItemModel("sword", SWORD)});
			m.put(DisplayItem.FOOD, new IItemModel[] {new ItemModel("food", ITEM)});
			m.put(DisplayItem.BOW, new IItemModel[] {new ItemModel("bow_pulling_0", BOW), new ItemModel("bow_pulling_1", BOW), new ItemModel("bow_pulling_2", BOW)});
			m.put(DisplayItem.CROSSBOW, new IItemModel[] {new ItemModel("crossbow", CROSSBOW), new ItemModel("crossbow_pulling_0", CROSSBOW),
					new ItemModel("crossbow_pulling_1", CROSSBOW), new ItemModel("crossbow_pulling_2", CROSSBOW)});
			m.put(DisplayItem.SPYGLASS, new IItemModel[] {new SpyglassModel()});
			m.put(DisplayItem.GOAT_HORN, new IItemModel[] {new ItemModel("goat_horn", GOAT_HORN)});
			m.put(DisplayItem.BRUSH, new IItemModel[] {new ItemModel("brush", BRUSH)});
			m.put(DisplayItem.SPEAR, new IItemModel[] {new ItemModel("spear", SPEAR)});
			models = m;
		}
		return models;
	}

	/** number of states (bow 3, crossbow 4, others 1); 0 for NONE / unknown */
	public static int states(DisplayItem item) {
		IItemModel[] m = item != null ? models().get(item) : null;
		return m != null ? m.length : 0;
	}

	/** geometry of one item model in the slot frame, Blockbench pixels (triangles) */
	public static final class Geo {
		public final String texture;
		public final double[] pos, uv, normal;

		Geo(String texture, double[] pos, double[] uv, double[] normal) {
			this.texture = texture;
			this.pos = pos;
			this.uv = uv;
			this.normal = normal;
		}

		public int vertexCount() {
			return pos.length / 3;
		}
	}

	/** null for NONE / an item without a model */
	public static Geo geometry(DisplayItem item, int state, ItemSlot hand) {
		IItemModel[] ms = item != null ? models().get(item) : null;
		if (ms == null || ms.length == 0)return null;
		int st = Math.max(0, Math.min(state, ms.length - 1));
		String key = item.name() + "/" + st + "/" + hand.name();
		Geo g = CACHE.get(key);
		if (g != null)return g;
		IItemModel model = ms[st];
		Capture cap = new Capture();
		model.render(new MatrixStack(), cap, hand);
		g = cap.toGeo(model.getTexture());
		CACHE.put(key, g);
		return g;
	}

	public static void clearCache() {
		CACHE.clear();
	}

	/** records the vertices CPM's model classes emit (quads, as Minecraft draws them) */
	private static final class Capture implements VertexBuffer {
		private final List<float[]> verts = new ArrayList<>();
		private float[] cur = new float[8];

		@Override
		public VertexBuffer pos(float x, float y, float z) {
			cur[0] = x;
			cur[1] = y;
			cur[2] = z;
			return this;
		}

		@Override
		public VertexBuffer tex(float u, float v) {
			cur[3] = u;
			cur[4] = v;
			return this;
		}

		@Override
		public VertexBuffer color(float red, float green, float blue, float alpha) {
			return this;
		}

		@Override
		public VertexBuffer normal(float x, float y, float z) {
			cur[5] = x;
			cur[6] = y;
			cur[7] = z;
			return this;
		}

		@Override
		public void endVertex() {
			verts.add(cur);
			cur = new float[8];
		}

		@Override
		public void finish() {
		}

		/** quads -> 2 triangles each; CPM axes -> Blockbench pixels */
		Geo toGeo(String texture) {
			int quads = verts.size() / 4;
			double[] p = new double[quads * 6 * 3];
			double[] u = new double[quads * 6 * 2];
			double[] n = new double[quads * 6 * 3];
			int[] order = {0, 1, 2, 0, 2, 3};
			int vi = 0;
			for (int q = 0; q < quads; q++) {
				for (int k : order) {
					float[] v = verts.get(q * 4 + k);
					p[vi * 3] = -16.0 * v[0];
					p[vi * 3 + 1] = -16.0 * v[1];
					p[vi * 3 + 2] = 16.0 * v[2];
					u[vi * 2] = v[3];
					u[vi * 2 + 1] = v[4];
					n[vi * 3] = -v[5];
					n[vi * 3 + 1] = -v[6];
					n[vi * 3 + 2] = v[7];
					vi++;
				}
			}
			return new Geo(texture, p, u, n);
		}
	}
}
