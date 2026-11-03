//! The single definition of the contract between the phone core and the Beacon:
//! Chat event stream, Call record, push envelope and Beacon CLI output formats,
//! plus the version compatibility rules. Each format lands here with the ticket
//! that first needs it.

/// Version of the core <-> Beacon contract defined by this crate.
pub const CONTRACT_VERSION: u32 = 1;

pub mod agent_status;
pub mod beacon;
pub mod calls;
pub mod chat;
pub mod push;
