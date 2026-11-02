package com.tom.cpm.blockbench.util;

import java.util.function.Consumer;

import com.tom.cpm.blockbench.PluginStart;
import com.tom.cpm.blockbench.proxy.Action.Condition;
import com.tom.cpm.blockbench.proxy.Action.ConditionMethod;

import jsinterop.base.Js;

public class ConditionUtil {

	public static void and(Condition cond, Consumer<Condition> set, ConditionMethod condition) {
		// CPM+ : Blockbench conditions can be a function, a boolean or an object with or without 'method'.
		// The old code assumed an object with a method: a function/boolean condition (or one with only
		// 'formats') produced a combined method that threw while the toolbar was rendered.
		String t = Js.typeof(cond);
		if(cond == null || "undefined".equals(t)) {
			Condition c = new Condition();
			c.method = condition;
			set.accept(c);
			PluginStart.cleanup.add(() -> set.accept(Js.uncheckedCast(Js.undefined())));
		} else if("function".equals(t)) {
			ConditionMethod orig = Js.uncheckedCast(cond);
			Condition c = new Condition();
			c.method = ctx -> safe(orig, ctx) && safe(condition, ctx);
			set.accept(c);
			PluginStart.cleanup.add(() -> set.accept(cond));
		} else if("boolean".equals(t)) {
			boolean b = Js.isTruthy(cond);
			Condition c = new Condition();
			c.method = ctx -> b && safe(condition, ctx);
			set.accept(c);
			PluginStart.cleanup.add(() -> set.accept(cond));
		} else if(cond.method == null) {
			cond.method = ctx -> safe(condition, ctx);
			PluginStart.cleanup.add(() -> cond.method = Js.uncheckedCast(Js.undefined()));
		} else
			PluginStart.cleanup.add(new FieldReplace<>(cond, a -> a.method, (a, b) -> a.method = b, condition, (a, b) -> c -> (safe(a, c) && safe(b, c))));
	}

	/** CPM+ : an exception inside a Blockbench condition breaks toolbar/menu rendering */
	private static boolean safe(ConditionMethod m, Object ctx) {
		if(m == null)return true;
		try {
			return m.check(ctx);
		} catch (Throwable e) {
			return true;
		}
	}
}
