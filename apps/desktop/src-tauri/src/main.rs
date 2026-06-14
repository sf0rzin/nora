// `windows_subsystem = "windows"` PRECISA viver no crate-root do BINARIO (este main.rs).
// Em lib.rs o atributo e ignorado pelo linker e o .exe sai no subsistema "console" —
// por isso uma janela de CMD preta abria ao INICIAR o app. Aqui ele tem efeito.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    nora_desktop_lib::run()
}
