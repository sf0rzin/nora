use std::sync::{Arc, Mutex};
use tauri::{AppHandle, Manager, WebviewWindow};

pub type StealthModeState = Arc<Mutex<bool>>;

#[tauri::command]
pub fn set_stealth_mode(
    app_handle: AppHandle,
    state: tauri::State<'_, StealthModeState>,
    enabled: bool,
) -> Result<(), String> {
    #[cfg(debug_assertions)]
    eprintln!("[stealth_mode] set_stealth_mode called, enabled={}", enabled);

    {
        let mut s = state.lock().map_err(|e| {
            #[cfg(debug_assertions)]
            eprintln!("[stealth_mode] failed to lock state: {}", e);
            e.to_string()
        })?;
        *s = enabled;
    }

    let main_result = if let Some(main) = app_handle.get_webview_window("main") {
        #[cfg(debug_assertions)]
        eprintln!("[stealth_mode] applying to main window");
        set_stealth_for_window(&main, enabled)
    } else {
        #[cfg(debug_assertions)]
        eprintln!("[stealth_mode] main window not found");
        Ok(())
    };

    let overlay_result = if let Some(overlay) = app_handle.get_webview_window("overlay") {
        #[cfg(debug_assertions)]
        eprintln!("[stealth_mode] applying to overlay window");
        set_stealth_for_window(&overlay, enabled)
    } else {
        #[cfg(debug_assertions)]
        eprintln!("[stealth_mode] overlay window not found (may be hidden)");
        Ok(())
    };

    if let Err(e) = &main_result {
        #[cfg(debug_assertions)]
        eprintln!("[stealth_mode] main window error: {}", e);
    }
    if let Err(e) = &overlay_result {
        #[cfg(debug_assertions)]
        eprintln!("[stealth_mode] overlay window error: {}", e);
    }

    main_result?;
    overlay_result?;

    #[cfg(debug_assertions)]
    eprintln!("[stealth_mode] set_stealth_mode completed successfully");
    Ok(())
}

#[tauri::command]
pub fn get_stealth_mode(state: tauri::State<'_, StealthModeState>) -> Result<bool, String> {
    let s = state.lock().map_err(|e| e.to_string())?;
    #[cfg(debug_assertions)]
    eprintln!("[stealth_mode] get_stealth_mode returning {}", *s);
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
        #[cfg(debug_assertions)]
        eprintln!("[stealth_mode] macOS stealth mode is a no-op in MVP");
        Ok(())
    }
}

#[cfg(target_os = "windows")]
fn set_stealth_windows(window: &WebviewWindow, enabled: bool) -> Result<(), String> {
    use windows::Win32::UI::WindowsAndMessaging::{
        SetWindowDisplayAffinity, WDA_EXCLUDEFROMCAPTURE, WDA_NONE,
    };

    #[cfg(debug_assertions)]
    eprintln!("[stealth_mode] Windows: getting HWND...");

    let hwnd = window.hwnd().map_err(|e| {
        let msg = format!("Failed to get HWND: {}", e);
        #[cfg(debug_assertions)]
        eprintln!("[stealth_mode] {}", msg);
        msg
    })?;

    #[cfg(debug_assertions)]
    eprintln!("[stealth_mode] Windows: calling SetWindowDisplayAffinity(enabled={})", enabled);

    unsafe {
        SetWindowDisplayAffinity(
            hwnd,
            if enabled {
                WDA_EXCLUDEFROMCAPTURE
            } else {
                WDA_NONE
            },
        )
        .map_err(|e| {
            let msg = format!("SetWindowDisplayAffinity failed: {:?}", e);
            #[cfg(debug_assertions)]
            eprintln!("[stealth_mode] {}", msg);
            msg
        })?;
    }

    #[cfg(debug_assertions)]
    eprintln!("[stealth_mode] Windows: SetWindowDisplayAffinity OK");
    Ok(())
}

#[cfg(target_os = "linux")]
fn set_stealth_linux(window: &WebviewWindow, enabled: bool) -> Result<(), String> {
    use raw_window_handle::{HasWindowHandle, RawWindowHandle};
    use std::ffi::CString;
    use x11::xlib::{
        PropModeReplace, XA_ATOM, XChangeProperty, XFlush, XInternAtom, XOpenDisplay,
    };

    #[cfg(debug_assertions)]
    eprintln!("[stealth_mode] Linux: getting window handle...");

    let handle = window.window_handle().map_err(|e| {
        let msg = format!("Failed to get window handle: {}", e);
        #[cfg(debug_assertions)]
        eprintln!("[stealth_mode] {}", msg);
        msg
    })?;

    let x11_window = match handle.as_raw() {
        RawWindowHandle::Xlib(xlib) => {
            #[cfg(debug_assertions)]
            eprintln!("[stealth_mode] Linux: Xlib window id={}", xlib.window);
            xlib.window
        }
        RawWindowHandle::Xcb(xcb) => {
            #[cfg(debug_assertions)]
            eprintln!("[stealth_mode] Linux: XCB window id={}", xcb.window.get());
            xcb.window.get() as u64
        }
        RawWindowHandle::Wayland(_) => {
            let msg = "Wayland display server detected. Stealth mode is not supported on Wayland because there is no standard API for a window to exclude itself from screen captures. Use X11 or run with GDK_BACKEND=x11.".to_string();
            #[cfg(debug_assertions)]
            eprintln!("[stealth_mode] {}", msg);
            return Err(msg);
        }
        other => {
            let msg = format!("Unsupported Linux display server: {:?}", other);
            #[cfg(debug_assertions)]
            eprintln!("[stealth_mode] {}", msg);
            return Err(msg);
        }
    };

    unsafe {
        let display = XOpenDisplay(std::ptr::null());
        if display.is_null() {
            let msg = "Failed to open X11 display".to_string();
            #[cfg(debug_assertions)]
            eprintln!("[stealth_mode] {}", msg);
            return Err(msg);
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

        #[cfg(debug_assertions)]
        eprintln!(
            "[stealth_mode] Linux: XChangeProperty(window={}, atoms={:?})",
            x11_window, atoms
        );

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

    #[cfg(debug_assertions)]
    eprintln!("[stealth_mode] Linux: X11 property set OK");
    Ok(())
}
