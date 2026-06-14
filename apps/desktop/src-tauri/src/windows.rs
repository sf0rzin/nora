use tauri::{AppHandle, Manager, PhysicalPosition};

/// Mostra/esconde a janela de dock flutuante.
///
/// A dock fica posicionada no centro-rodapé do monitor primário, com um
/// pequeno offset vertical pra não encostar na taskbar. O posicionamento
/// só acontece na primeira vez que ela aparece — depois disso o usuário
/// pode arrastar livremente pelo handle de drag.
#[tauri::command]
pub fn toggle_dock(app_handle: AppHandle, show: bool) -> Result<(), String> {
    let Some(window) = app_handle.get_webview_window("dock") else {
        return Err("Dock window not found".to_string());
    };

    if show {
        // Reposiciona no centro-rodapé sempre que mostrar — usuário pode
        // arrastar depois mas o padrão "aparece embaixo" é mais previsível
        // do que herdar a posição anterior.
        if let Some(monitor) = window.current_monitor().map_err(|e| e.to_string())? {
            let scale = monitor.scale_factor();
            let monitor_size = monitor.size();
            let monitor_pos = monitor.position();
            let window_size = window.outer_size().map_err(|e| e.to_string())?;
            let bottom_margin = (28.0 * scale) as i32;
            let x = monitor_pos.x
                + ((monitor_size.width as i32 - window_size.width as i32) / 2);
            let y = monitor_pos.y + monitor_size.height as i32
                - window_size.height as i32
                - bottom_margin;
            let _ = window.set_position(PhysicalPosition { x, y });
        }
        let _ = window.show();
        // Não chamamos set_focus — queremos que o dock fique visível mas
        // sem roubar o foco do app em primeiro plano (Meet/Zoom/Teams).
    } else {
        let _ = window.hide();
    }
    Ok(())
}

/// Mostra e foca a janela principal (NORA Desktop).
#[tauri::command]
pub fn focus_main_window(app_handle: AppHandle) -> Result<(), String> {
    let Some(window) = app_handle.get_webview_window("main") else {
        return Err("Main window not found".to_string());
    };
    let _ = window.show();
    let _ = window.unminimize();
    let _ = window.set_focus();
    Ok(())
}

/// Mostra e foca a janela da overlay.
#[tauri::command]
pub fn focus_overlay_window(app_handle: AppHandle) -> Result<(), String> {
    let Some(window) = app_handle.get_webview_window("overlay") else {
        return Err("Overlay window not found".to_string());
    };
    let _ = window.show();
    let _ = window.set_focus();
    Ok(())
}

/// Mostra e foca a janela de gravacao nativa (recorder).
///
/// A gravacao nativa (mic + audio do sistema + transcricao ao vivo) vive nessa
/// janela separada porque cada janela Tauri e um contexto JS isolado — a main
/// carrega o web remoto (nora.systems), que nao tem acesso aos comandos nativos.
#[tauri::command]
pub fn show_recorder(app_handle: AppHandle) -> Result<(), String> {
    let w = app_handle
        .get_webview_window("recorder")
        .ok_or("recorder window not found")?;
    w.show().map_err(|e| e.to_string())?;
    w.unminimize().ok();
    w.set_focus().map_err(|e| e.to_string())?;
    Ok(())
}
