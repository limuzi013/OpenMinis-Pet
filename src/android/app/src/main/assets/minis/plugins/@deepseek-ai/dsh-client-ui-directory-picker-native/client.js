window.__ModuleLoader__.load({
	id: "@deepseek-ai/dsh-client-ui-directory-picker-native",
	factory: (require) => {
		var module = { exports: {} };
		var exports = module.exports;
		Object.defineProperty(exports, Symbol.toStringTag, { value: "Module" });
		let react = require("react");
		//#region lib/types/client/flow.js
		/**
		* The native picking occupant (package-internal; the `./client` surface
		* exposes only the Loader exports). Same-package tests exercise it directly
		* through this module.
		*/
		/**
		* Renderless flow occupant: each rising `open` edge runs exactly one pick and
		* reports exactly one outcome; the ref arms once per open so re-renders (and
		* an adoption keeping `open` true while `busy`) never launch a second
		* chooser. The owner withdrawing `open` re-arms the next request.
		* @param props - owner conversation plus the injected pick call.
		* @returns nothing — the native chooser renders on the host display.
		*/
		function NativeDirectoryFlow(props) {
			const { open, pick } = props;
			const armed = (0, react.useRef)(false);
			const outcome = (0, react.useRef)(props);
			outcome.current = props;
			const alive = (0, react.useRef)(true);
			(0, react.useEffect)(() => {
				alive.current = true;
				return () => {
					alive.current = false;
				};
			}, []);
			(0, react.useEffect)(() => {
				if (!open) {
					armed.current = false;
					return;
				}
				if (armed.current) return;
				armed.current = true;
				pick().then((path) => {
					if (!alive.current) return;
					if (path === null) outcome.current.onCancel();
					else outcome.current.onPicked(path);
				}, (reason) => {
					if (!alive.current) return;
					outcome.current.onError(reason instanceof Error ? reason.message : String(reason));
				});
			}, [open, pick]);
			return null;
		}
		//#endregion
		//#region lib/types/client/index.js
		/** Required services (cordis fiber inject): the slot registry and the wire-facing workspace service. */
		const inject = ["slots", "workspaces"];
		/**
		* Client plugin body: register the renderless native flow into both
		* directory-flow holes through `slots.inject()` because the ui-workspace
		* entries may activate later or replace their declarations.
		* @param ctx - client root context.
		*/
		function apply(ctx) {
			// Android cannot launch its SAF picker from a remote browser. Treat the
			// stock DSH "pick directory" flow as creation of an App conversation
			// group instead; workspace.create maps this stable synthetic path to the
			// same native FolderEntity used by the phone session list.
			const injected = () => ({ pick: async () => {
				const raw = window.prompt("输入工作区名称（会与手机会话分组同步）", "新工作区");
				if (raw === null) return null;
				const name = raw.trim();
				if (name === "") throw new Error("工作区名称不能为空");
				if (name.length > 120) throw new Error("工作区名称不能超过 120 个字符");
				return `/var/minis/workspace/groups/${encodeURIComponent(name)}`;
			} });
			ctx.slots.inject("conversation.hero.workspace.directoryFlow", () => ctx.slots.inject("sidebar.workspaces.directoryFlow", function* () {
				yield ctx.slots.register({
					name: "conversation.hero.workspace.directoryFlow",
					inject: injected
				}, NativeDirectoryFlow);
				yield ctx.slots.register({
					name: "sidebar.workspaces.directoryFlow",
					inject: injected
				}, NativeDirectoryFlow);
			}));
		}
		//#endregion
		exports.apply = apply;
		exports.inject = inject;
		return module.exports;
	}
});

//# sourceMappingURL=client.js.map