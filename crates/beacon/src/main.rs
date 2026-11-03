//! `aries-beacon`: runs on a Host. Subcommands arrive with their tickets.

fn main() {
    match std::env::args().nth(1).as_deref() {
        Some("--version" | "version") => println!(
            "aries-beacon {} (contract {})",
            env!("CARGO_PKG_VERSION"),
            aries_protocol::CONTRACT_VERSION
        ),
        _ => {
            eprintln!("usage: aries-beacon --version");
            std::process::exit(2);
        }
    }
}
