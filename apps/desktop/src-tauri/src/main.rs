// `windows_subsystem = "windows"` MUST live in the crate-root of the BINARY (this main.rs).
// In lib.rs the attribute is ignored by the linker and the .exe comes out in the "console" subsystem —
// that is why a black CMD window opened when STARTING the app. Here it takes effect.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    nora_desktop_lib::run()
}
