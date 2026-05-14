use std::sync::{Arc, Mutex};
use tauri::{AppHandle, Manager, WebviewWindow};

pub type StealthModeState = Arc<Mutex<bool>>;

#[tauri::command]
pub fn set_stealth_mode(
    app_handle: AppHandle,
    state: tauri::State<'_, StealthModeState>,
    enabled: bool,
) -> Result<(), String> {
    {
        let mut s = state.lock().map_err(|e| e.to_string())?;
        *s = enabled;
    }

    if let Some(main) = app_handle.get_webview_window("main") {
        set_stealth_for_window(&main, enabled)?;
    }
    if let Some(overlay) = app_handle.get_webview_window("overlay") {
        set_stealth_for_window(&overlay, enabled)?;
    }

    Ok(())
}

#[tauri::command]
pub fn get_stealth_mode(state: tauri::State<'_, StealthModeState>) -> Result<bool, String> {
    let s = state.lock().map_err(|e| e.to_string())?;
    Ok(*s)
}

fn set_stealth_for_window(window: &WebviewWindow, enabled: bool) -> Result<(), String> {
    #[cfg(target_os = "windows")]
    {
        set_stealth_windows(window, enabled)
    }

    #[cfg(target_os = "linux")]
    {
        set_stealth_linux(window, enabled)
    }

    #[cfg(target_os = "macos")]
    {
        // Stealth mode não implementado para macOS no MVP.
        // A janela continua visível normalmente.
        Ok(())
    }
}

#[cfg(target_os = "windows")]
fn set_stealth_windows(window: &WebviewWindow, enabled: bool) -> Result<(), String> {
    use windows::Win32::UI::WindowsAndMessaging::{
        SetWindowDisplayAffinity, WDA_EXCLUDEFROMCAPTURE, WDA_NONE,
    };

    let hwnd = window.hwnd().map_err(|e| format!("Failed to get HWND: {}", e))?;

    unsafe {
        SetWindowDisplayAffinity(
            hwnd,
            if enabled {
                WDA_EXCLUDEFROMCAPTURE
            } else {
                WDA_NONE
            },
        )
        .map_err(|e| format!("SetWindowDisplayAffinity failed: {:?}", e))?;
    }

    Ok(())
}

#[cfg(target_os = "linux")]
fn set_stealth_linux(window: &WebviewWindow, enabled: bool) -> Result<(), String> {
    use raw_window_handle::{HasWindowHandle, RawWindowHandle};
    use std::ffi::CString;
    use x11::xlib::{
        PropModeReplace, XA_ATOM, XChangeProperty, XFlush, XInternAtom, XOpenDisplay,
    };

    let handle = window
        .window_handle()
        .map_err(|e| format!("Failed to get window handle: {}", e))?;

    let x11_window = match handle.as_raw() {
        RawWindowHandle::Xlib(xlib) => xlib.window,
        RawWindowHandle::Xcb(xcb) => xcb.window.get() as u64,
        _ => return Err("Unsupported Linux display server".to_string()),
    };

    unsafe {
        let display = XOpenDisplay(std::ptr::null());
        if display.is_null() {
            return Err("Failed to open X11 display".to_string());
        }

        let net_wm_state = XInternAtom(
            display,
            CString::new("_NET_WM_STATE").unwrap().as_ptr(),
            0,
        );
        let skip_taskbar = XInternAtom(
            display,
            CString::new("_NET_WM_STATE_SKIP_TASKBAR").unwrap().as_ptr(),
            0,
        );
        let skip_pager = XInternAtom(
            display,
            CString::new("_NET_WM_STATE_SKIP_PAGER").unwrap().as_ptr(),
            0,
        );

        let atoms: Vec<u64> = if enabled {
            vec![skip_taskbar as u64, skip_pager as u64]
        } else {
            vec![]
        };

        XChangeProperty(
            display,
            x11_window,
            net_wm_state,
            XA_ATOM,
            32,
            PropModeReplace,
            atoms.as_ptr() as *const u8,
            atoms.len() as i32,
        );

        XFlush(display);
    }

    Ok(())
}
