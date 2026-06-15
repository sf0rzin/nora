use tauri::{AppHandle, Manager, PhysicalPosition};

#[cfg(target_os = "windows")]
use tauri::WebviewWindow;

/// Remove a borda/contorno que o DWM do Windows 11 desenha em volta de QUALQUER
/// top-level window — inclusive janelas transparentes sem decoração (dock,
/// overlay). Sem isso aparece um retângulo arredondado fantasma em volta da
/// barra de vidro (bug reportado: "contorno como se fosse uma janela do
/// Windows"). Pintamos a cor da borda como DWMWA_COLOR_NONE.
///
/// Best-effort: em versões antigas do Windows o atributo é ignorado, então
/// só logamos o erro em debug e seguimos.
#[cfg(target_os = "windows")]
pub fn remove_window_border(window: &WebviewWindow) {
    use windows::Win32::Foundation::HWND;
    use windows::Win32::Graphics::Dwm::{DwmSetWindowAttribute, DWMWA_BORDER_COLOR};

    // DWMWA_COLOR_NONE — desliga o desenho da borda pelo DWM.
    const DWMWA_COLOR_NONE: u32 = 0xFFFF_FFFE;

    let hwnd_raw = match window.hwnd() {
        Ok(h) => h,
        Err(e) => {
            #[cfg(debug_assertions)]
            eprintln!("[windows] remove_window_border: failed to get HWND: {}", e);
            let _ = e;
            return;
        }
    };
    // Tauri depende do windows 0.61; nosso Cargo.toml usa 0.62 — reconstruímos o
    // HWND da versão correta a partir do ponteiro bruto (mesmo padrão do stealth).
    let hwnd = HWND(hwnd_raw.0);
    let color = DWMWA_COLOR_NONE;

    unsafe {
        if let Err(e) = DwmSetWindowAttribute(
            hwnd,
            DWMWA_BORDER_COLOR,
            &color as *const u32 as *const core::ffi::c_void,
            std::mem::size_of::<u32>() as u32,
        ) {
            #[cfg(debug_assertions)]
            eprintln!("[windows] DwmSetWindowAttribute(BORDER_COLOR) failed: {:?}", e);
            let _ = e;
        }
    }
}

/// Mostra/esconde a janela de dock flutuante.
///
/// A dock fica posicionada no topo-centro do monitor primário, com um
/// pequeno offset vertical pra não encostar na borda. O posicionamento
/// só acontece quando ela aparece — depois disso o usuário pode arrastar
/// livremente pelo handle de drag. A barra é redimensionável por JS
/// (setSize ao expandir/colapsar), então usamos a largura atual da janela
/// (outer_size) pra centralizar corretamente em qualquer estado.
#[tauri::command]
pub fn toggle_dock(app_handle: AppHandle, show: bool) -> Result<(), String> {
    let Some(window) = app_handle.get_webview_window("dock") else {
        return Err("Dock window not found".to_string());
    };

    if show {
        // Reposiciona no topo-centro sempre que mostrar — usuário pode
        // arrastar depois, mas o padrão "aparece no topo" é mais previsível
        // (estilo Cluely) do que herdar a posição anterior.
        if let Some(monitor) = window.current_monitor().map_err(|e| e.to_string())? {
            let scale = monitor.scale_factor();
            let monitor_size = monitor.size();
            let monitor_pos = monitor.position();
            let window_size = window.outer_size().map_err(|e| e.to_string())?;
            let top_margin = (24.0 * scale) as i32;
            let x = monitor_pos.x
                + ((monitor_size.width as i32 - window_size.width as i32) / 2);
            let y = monitor_pos.y + top_margin;
            let _ = window.set_position(PhysicalPosition { x, y });
        }
        let _ = window.show();
        // Mata o contorno fantasma do DWM agora que a janela tem HWND e está
        // visível (chamar com a janela escondida força o handle e pode pintar
        // branco — mesmo cuidado do stealth).
        #[cfg(target_os = "windows")]
        remove_window_border(&window);
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

/// Abre o explorador de arquivos na pasta de logs do app.
///
/// Em release o binario roda com `windows_subsystem = "windows"` (sem console),
/// entao o `desktop.log` escrito pelo `start_recording` e a unica forma de o
/// usuario diagnosticar "cliquei iniciar e nada aconteceu". Este comando abre
/// a pasta de logs no Explorer/Finder via o plugin shell — best-effort, mas
/// retorna erro pro JS caso a pasta nao exista ou o open falhe.
#[tauri::command]
pub fn open_log_dir(app: AppHandle) -> Result<(), String> {
    use tauri_plugin_shell::ShellExt;

    let dir = app
        .path()
        .app_log_dir()
        .map_err(|e| format!("failed to resolve log dir: {}", e))?;
    // Garante que a pasta existe antes de tentar abri-la.
    std::fs::create_dir_all(&dir).map_err(|e| format!("failed to create log dir: {}", e))?;

    let dir_str = dir.to_string_lossy().to_string();
    app.shell()
        .open(dir_str, None)
        .map_err(|e| format!("failed to open log dir: {}", e))?;
    Ok(())
}
