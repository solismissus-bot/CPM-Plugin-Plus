package com.tom.cpm.blockbench.ee;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import com.tom.cpl.gui.Frame;
import com.tom.cpl.gui.elements.MessagePopup;
import com.tom.cpm.blockbench.PluginStart;
import com.tom.cpm.blockbench.convert.ProjectConvert;
import com.tom.cpm.blockbench.util.PopupDialogs.UserException;
import com.tom.cpm.web.client.WebMC;
import com.tom.cpm.web.client.render.RenderSystem.WindowEx;
import com.tom.cpm.web.client.resources.Resources;
import com.tom.cpm.web.client.util.I18n;
import com.tom.ugwt.client.UGWTContext;

import elemental2.core.ArrayBuffer;
import elemental2.dom.DomGlobal;
import elemental2.dom.EventListener;
import elemental2.dom.HTMLScriptElement;
import elemental2.dom.MessageEvent;
import elemental2.promise.Promise;
import jsinterop.annotations.JsOverlay;
import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsProperty;
import jsinterop.annotations.JsType;
import jsinterop.base.Js;

public class WebEmbeddedEditor implements EmbeddedEditor {
	private WindowEx window;
	private Map<String, Consumer<Message>> listeners = new HashMap<>();
	private boolean loadingFinished;

	private static final String MSG_CPM_READY = "cpm_on_ready";
	private static final String MSG_OPEN_IN_BB = "cpm_open_bb";
	private static final String MSG_OPEN_IN_BB_FIN = "cpm_open_finish_bb";
	// CPM+ : these were all "cpm_on_ready" (same as MSG_CPM_READY): the FIN listener replaced the ready
	// listener and a later ready message resolved the wrong promise. Both sides run this same code.
	private static final String MSG_OPEN_PROJECT = "cpm_open_project";
	private static final String MSG_OPEN_PROJECT_FIN = "cpm_open_project_fin";
	private static final String MSG_CLOSED = "window_closed";
	private static final int READY_TIMEOUT_MS = 60000;

	@Override
	public Promise<EmbeddedEditor> open() {
		return new Promise<>((res, rej) -> {
			window = Js.uncheckedCast(DomGlobal.window.open("", "_blank"));
			// CPM+ : window.open returns null when popups are blocked (web) or denied by the host
			if (window == null || Js.isFalsy(window) || window.document == null) {
				window = null;
				rej.onInvoke(new UserException("Could not open the Embedded Editor window. Allow popups for this site and try again."));
				return;
			}
			window.document.write(DomGlobal.atob(Resources.getResource("assets/cpmblockbench/ee/embedded_page.html")));
			window.document.close();
			final WindowEx myWindow = window;

			EventListener eb = evIn -> {
				MessageEvent<Object> event = (MessageEvent<Object>) evIn;
				if (window == null)return;
				if (event.origin != window.location.origin &&
						!("null".equals(event.origin) && "file://".equals(window.location.origin))
						) return;
				Message msg = Js.uncheckedCast(event.data);
				if (msg != null && msg.id != null) {
					Consumer<Message> mc = listeners.get(msg.id);
					if (mc != null)mc.accept(msg);
				}
			};
			DomGlobal.window.addEventListener("message", eb);
			// CPM+ : remove the host listener and close the editor window when the plugin unloads
			PluginStart.cleanup.add(() -> {
				DomGlobal.window.removeEventListener("message", eb);
				try {
					if (!myWindow.closed)myWindow.close();
				} catch (Throwable e) {
				}
			});

			listeners.put(MSG_CLOSED, msg -> {
				if (!loadingFinished)rej.onInvoke(msg.data != null ? msg.data : "Window closed");
				window = null;
				DomGlobal.window.removeEventListener("message", eb);
			});

			listeners.put(MSG_CPM_READY, msg -> {
				DomGlobal.console.log("Embedded Editor Ready");
				loadingFinished = true;
				res.onInvoke(this);
			});

			// CPM+ : don't wait forever if the editor never becomes ready
			DomGlobal.setTimeout(__ -> {
				if (!loadingFinished)rej.onInvoke(new UserException("The Embedded Editor did not start within " + (READY_TIMEOUT_MS / 1000) + " seconds."));
			}, READY_TIMEOUT_MS);

			listeners.put(MSG_OPEN_IN_BB, msg -> {
				DomGlobal.console.log("Opening CPM project from Embedded editor");
				DomGlobal.window.focus();
				ProjectConvert.parse(msg.data_buf).then(___ -> {
					sendRemote(Message.make(MSG_OPEN_IN_BB_FIN, ""));
					return null;
				}).catch_(ex -> {
					sendRemote(Message.make(MSG_OPEN_IN_BB_FIN, "Error: " + ex));
					return null;
				});
			});

			HTMLScriptElement scriptZip = Js.uncheckedCast(window.document.createElement("script"));
			scriptZip.textContent = DomGlobal.atob(Resources.getResource("assets/cpmblockbench/ee/jszip.min.js"));
			window.document.body.appendChild(scriptZip);

			String bootstrap = DomGlobal.atob(Resources.getResource("assets/cpmblockbench/ee/web_bootstrap.js"));
			HTMLScriptElement scriptBootstrap = Js.uncheckedCast(window.document.createElement("script"));
			scriptBootstrap.textContent = bootstrap.
					replace("$ver", System.getProperty("cpm.version")).
					replace("$platform", DomGlobal.btoa(WebMC.platform));
			window.document.body.appendChild(scriptBootstrap);

			HTMLScriptElement script = Js.uncheckedCast(window.document.createElement("script"));
			script.textContent = "(" + UGWTContext.getAppScript() + ")()";
			window.document.body.appendChild(script);
		});
	}

	@Override
	public void focus() {
		window.focus();
	}

	@Override
	public void close() {
		window.close();
	}

	@Override
	public boolean isClosed() {
		return window == null || window.closed;
	}

	@Override
	public void onReady() {
		DomGlobal.window.addEventListener("message", evIn -> {
			MessageEvent<Object> event = (MessageEvent<Object>) evIn;
			if (event.origin != DomGlobal.window.location.origin &&
					!("null".equals(event.origin) && "file://".equals(DomGlobal.window.location.origin))
					) return;
			Message msg = Js.uncheckedCast(event.data);
			if (msg.id != null) {
				Consumer<Message> mc = listeners.get(msg.id);
				if (mc != null)mc.accept(msg);
			}
		});
		listeners.put(MSG_OPEN_PROJECT, m -> {
			if (m.data_buf != null) {
				EmbeddedEditor.openProjectFromBuffer(m.data_buf).then(__ -> {
					send(Message.make(MSG_OPEN_PROJECT_FIN, ""));
					return null;
				}).catch_(e -> {
					send(Message.make(MSG_OPEN_PROJECT_FIN, "Error: " + e));
					return null;
				});
			}
		});
		send(Message.make(MSG_CPM_READY, ""));
	}

	private void send(Message msg) {
		DomGlobal.window.opener.postMessage(msg, DomGlobal.window.location.origin);
	}

	private void sendRemote(Message msg) {
		window.postMessage(msg, DomGlobal.window.location.origin);
	}

	@Override
	public Promise<Void> openProject(ArrayBuffer dt) {
		return new Promise<>((res, rej) -> {
			DomGlobal.console.log("Opening project in embedded editor");
			if (isClosed()) {
				rej.onInvoke("Window closed");
				return;
			}
			listeners.put(MSG_OPEN_PROJECT_FIN, msg -> {
				DomGlobal.console.log(msg);
				listeners.remove(MSG_OPEN_PROJECT_FIN);
				// CPM+ : the embedded side answers "Error: ..." (e.g. unsaved changes); report it
				if (msg.data != null && msg.data.startsWith("Error"))
					rej.onInvoke(new UserException("The Embedded Editor could not open the project: " + msg.data));
				else
					res.onInvoke((Void) null);
			});
			sendRemote(Message.make(MSG_OPEN_PROJECT, dt));
		});
	}

	@Override
	public Promise<Void> openInHost(ArrayBuffer dt) {
		return new Promise<>((res, rej) -> {
			DomGlobal.console.log("Opening project in blockbench");
			listeners.put(MSG_OPEN_IN_BB_FIN, msg -> {
				DomGlobal.console.log(msg);
				res.onInvoke((Void) null);
				listeners.remove(MSG_OPEN_IN_BB_FIN);
			});
			send(Message.make(MSG_OPEN_IN_BB, dt));
			DomGlobal.window.opener.focus();

			Frame frm = WebMC.getInstance().getGui().getFrame();
			frm.openPopup(new MessagePopup(frm, I18n.get("label.cpm.info"), I18n.get("bb-label.openedInBB")));
		});
	}

	@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Object_$$")
	public static class Message {
		public String id;
		public String data;

		@JsProperty(name = "data")
		public ArrayBuffer data_buf;

		@JsOverlay
		public static Message make(String id, String content) {
			Message msg = new Message();
			msg.id = id;
			msg.data = content;
			return msg;
		}

		@JsOverlay
		public static Message make(String id, ArrayBuffer content) {
			Message msg = new Message();
			msg.id = id;
			msg.data_buf = content;
			return msg;
		}
	}
}
