//! Operation-scoped SSH key leases. Overlapping authentications share one
//! vault operation; the final lease drops the private key. No expiry/cache.
use crate::{CoreError, KeyProtection, KeyVault};
use russh::keys::PrivateKey;
use std::collections::HashMap;
use std::sync::{Arc, Condvar, Mutex, Weak};

#[derive(Default)]
pub(crate) struct KeyUnlocks {
    flights: Mutex<HashMap<Vec<u8>, Weak<Flight>>>,
    prompt: Mutex<()>,
}
struct Flight {
    result: Mutex<Option<Result<Arc<PrivateKey>, CoreError>>>,
    ready: Condvar,
}
pub(crate) struct KeyLease {
    pub key: Arc<PrivateKey>,
    _flight: Arc<Flight>,
}
#[derive(Clone)]
pub(crate) struct SshKey {
    sealed: Vec<u8>,
    protection: KeyProtection,
    vault: Arc<dyn KeyVault>,
    unlocks: Arc<KeyUnlocks>,
}
impl SshKey {
    pub fn new(
        sealed: Vec<u8>,
        protection: KeyProtection,
        vault: Arc<dyn KeyVault>,
        unlocks: Arc<KeyUnlocks>,
    ) -> Self {
        Self {
            sealed,
            protection,
            vault,
            unlocks,
        }
    }
    pub async fn open(&self) -> Result<KeyLease, CoreError> {
        let key = self.clone();
        crate::network_timeout::authorization(async move {
            tokio::task::spawn_blocking(move || key.open_blocking())
                .await
                .map_err(|e| CoreError::Vault {
                    detail: e.to_string(),
                })?
        })
        .await
    }
    fn open_blocking(&self) -> Result<KeyLease, CoreError> {
        let (flight, first) = {
            let mut flights = self.unlocks.flights.lock().expect("key flights");
            flights.retain(|_, f| f.strong_count() > 0);
            if let Some(flight) = flights.get(&self.sealed).and_then(Weak::upgrade) {
                (flight, false)
            } else {
                let flight = Arc::new(Flight {
                    result: Mutex::new(None),
                    ready: Condvar::new(),
                });
                flights.insert(self.sealed.clone(), Arc::downgrade(&flight));
                (flight, true)
            }
        };
        if first {
            let _prompt = self.unlocks.prompt.lock().expect("key prompt");
            let result = self
                .vault
                .open(self.sealed.clone(), self.protection)
                .map_err(CoreError::from)
                .and_then(|pem| {
                    PrivateKey::from_openssh(pem)
                        .map(Arc::new)
                        .map_err(|e| CoreError::Vault {
                            detail: e.to_string(),
                        })
                });
            *flight.result.lock().expect("key result") = Some(result);
            flight.ready.notify_all();
        }
        let key = {
            let mut result = flight.result.lock().expect("key result");
            while result.is_none() {
                result = flight.ready.wait(result).expect("key result");
            }
            result.as_ref().expect("authorized").clone()?
        };
        Ok(KeyLease {
            key,
            _flight: flight,
        })
    }
}
