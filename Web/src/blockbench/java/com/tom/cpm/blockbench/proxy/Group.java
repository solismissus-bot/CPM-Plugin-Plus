package com.tom.cpm.blockbench.proxy;

import com.tom.cpm.blockbench.format.GroupData;
import com.tom.cpm.blockbench.proxy.Vectors.JsVec3;
import com.tom.cpm.blockbench.proxy.three.Object3D;
import com.tom.cpm.shared.model.PlayerModelParts;
import com.tom.cpm.shared.model.RootModelType;
import com.tom.cpm.shared.model.render.VanillaModelPart;
import com.tom.ugwt.client.JsArrayE;

import elemental2.core.JsArray;
import jsinterop.annotations.JsFunction;
import jsinterop.annotations.JsOverlay;
import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsProperty;
import jsinterop.annotations.JsType;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Group_$$")
public class Group extends OutlinerNode {

	@JsOverlay
	public static final Group ROOT = Js.uncheckedCast("root");

	public static Group[] all;
	public static JsPropertyMap<Group> uuids;

	public boolean isOpen;
	public String name;
	public JsVec3 origin;
	public JsVec3 rotation;
	public Group parent;
	public boolean is_rotation_subgroup, export, visibility, is_catch_bone;
	public JsArrayE<OutlinerNode> children;
	public Object3D mesh;

	@JsProperty(name = "cpm_dva")
	public boolean disableVanillaAnim;

	@JsProperty(name = "cpm_copy_transform")
	public String copyTransform;

	@JsProperty(name = "cpm_hidden")
	public boolean hidden;

	@JsProperty(name = "cpm_default_visible")
	public boolean defaultVisible;

	@JsProperty(name = "cpm_current_visible")
	public boolean currentVisible;

	@JsProperty(name = "cpm_animator_init")
	public boolean animatorInit;

	public Group(GroupProperties ctr) {}

	@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Object_$$")
	public static class GroupProperties {
		public String name;
		public JsVec3 origin, rotation;
	}

	public native void extend(GroupProperties ctr);
	public native void createUniqueName(JsArray<Group> all);
	public native void addTo(Group gr);
	public native Group init();
	public native void resolve();

	// ------------------------------------------------------------------
	// CPM+ (W0B-INFRA): Blockbench 5 natives used by the modelling tools
	// ------------------------------------------------------------------
	/** the first selected group (getter), or undefined */
	public static Group first_selected;
	/** every selected group */
	public static Group[] multi_selected;
	/** registered group properties (name -> Property) */
	public static JsPropertyMap<Property> properties;
	public boolean locked;

	/** true when this node is (depth 0 = any level) below the given group */
	public native boolean isChildOf(Object group, int depth);
	public native boolean isChildOf(Object group);
	/** visits children recursively; type = a class (Cube, Group) or an array of classes or null; forSelf includes this group */
	public native void forEachChild(ChildCallback cb, Object type, boolean forSelf);
	public native void forEachChild(ChildCallback cb);
	/** copy of the group and everything inside (new uuids, sorted in after this group). Callers reset CPM ids (IdUtil). */
	public native Group duplicate();
	/** removes the group and its children; undo=true makes it one undo entry of its own */
	public native void remove(boolean undo);
	/** moves this node into parent (a Group or "root") at index (-1 = end) */
	public native Group addTo(Object parent, int index);
	/** moves the pivot to origin [x,y,z] keeping the children where they are */
	public native Group transferOrigin(Object origin);
	public native Group select();
	public native Group unselect();
	public native Group openUp();
	public native Object getSaveCopy(boolean project);
	public native Object getChildlessCopy(boolean keepUuid);
	public native Group sortInBefore(Object element, int offset);

	@JsFunction
	public interface ChildCallback {
		void accept(Object node);
	}

	@JsProperty(name = "cpm_dataCache")
	private GroupData data;

	@JsOverlay
	public final GroupData getData() {
		// CPM+ (G16): parse again when cpm_data was replaced since (Ctrl+Z / redo, restored snapshot, remote edit)
		if(data == null || !data.isFor(pluginData))data = new GroupData(this);
		return data;
	}

	@JsOverlay
	public final void applyVisible(boolean parent) {
		// CPM+ : respect what the user hid with the eye icon before entering Animate mode
		// (defaultVisible is captured by CPMVisuals and restored when leaving Animate mode)
		visibility = parent && currentVisible && (!animatorInit || defaultVisible);
		for (int i = 0; i < children.length; i++) {
			OutlinerNode elem = children.getAt(i);
			if (elem instanceof Group) {
				((Group) elem).applyVisible(visibility);
			} else if(elem instanceof Cube) {
				Cube c = (Cube) elem;
				c.visibility = visibility && (!c.animatorInit || c.defaultVisible);
			}
		}
	}

	@JsOverlay
	public final VanillaModelPart getRootType() {
		String name = this.name;
		if(name.endsWith("_dup")) {
			name = name.substring(0, name.length() - 4);
		}
		for(PlayerModelParts pmp : PlayerModelParts.VALUES) {
			if(pmp.getName().equalsIgnoreCase(name))return pmp;
		}
		for(RootModelType pmp : RootModelType.VALUES) {
			if(pmp.getName().equalsIgnoreCase(name))return pmp;
		}
		return null;
	}
}
