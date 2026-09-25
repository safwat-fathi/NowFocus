import { getCurrentWindow } from "@tauri-apps/api/window";
import { CloseIcon, MaximizeIcon, MinimizeIcon } from "./Icons";

const win = getCurrentWindow();

/** Custom-drawn window chrome — the design has no native OS close button,
 * only minimize/maximize and an explicit "close to tray" action. */
export function TitleBar({ suffix }: { suffix: string }) {
  return (
    <div className="title-bar">
      <div className="title-bar__drag" data-tauri-drag-region onDoubleClick={() => win.toggleMaximize()}>
        <span className="title-bar__mark" />
        NowFocus
        <span className="title-bar__suffix">{suffix}</span>
      </div>
      <button className="title-bar__btn" onClick={() => win.minimize()} title="Minimize">
        <MinimizeIcon />
      </button>
      <button className="title-bar__btn" onClick={() => win.toggleMaximize()} title="Maximize">
        <MaximizeIcon />
      </button>
      <button className="title-bar__btn title-bar__btn--close" onClick={() => win.hide()} title="Close to tray">
        <CloseIcon />
      </button>
    </div>
  );
}
