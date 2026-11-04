//! Network budgets exclude time waiting for foreground key authorization.
//! Nested budgets share the same clock, scoped to this connection attempt.
use std::{
    future::Future,
    sync::{Arc, Mutex},
    time::Duration,
};
use tokio::{sync::watch, time::Instant};
#[derive(Default)]
struct State {
    paused: Option<Instant>,
    excluded: Duration,
}
#[derive(Clone)]
struct Clock {
    state: Arc<Mutex<State>>,
    changed: watch::Sender<()>,
}
impl Clock {
    fn excluded(&self) -> Duration {
        let s = self.state.lock().expect("network clock");
        s.excluded + s.paused.map_or(Duration::ZERO, |t| t.elapsed())
    }
}
tokio::task_local! { static CLOCK: Clock; }
struct Pause(Clock);
impl Drop for Pause {
    fn drop(&mut self) {
        let mut state = self.0.state.lock().expect("network clock");
        if let Some(start) = state.paused.take() {
            state.excluded += start.elapsed();
        }
        self.0.changed.send_replace(());
    }
}
pub(crate) async fn authorization<T>(future: impl Future<Output = T>) -> T {
    let pause = CLOCK
        .try_with(|clock| {
            clock.state.lock().expect("network clock").paused = Some(Instant::now());
            clock.changed.send_replace(());
            Pause(clock.clone())
        })
        .ok();
    let result = future.await;
    drop(pause);
    result
}
pub(crate) fn timeout<T>(
    duration: Duration,
    future: impl Future<Output = T>,
) -> impl Future<Output = Result<T, ()>> {
    // Box before constructing the wrapper future: nested SSH budgets would
    // otherwise embed large child futures at every level. Boxing inside the
    // async block still leaves the unboxed input in its initial state.
    let mut future = Box::pin(future);
    async move {
        let clock = CLOCK.try_with(Clone::clone).unwrap_or_else(|_| Clock {
            state: Arc::default(),
            changed: watch::channel(()).0,
        });
        CLOCK
            .scope(clock.clone(), async move {
                let start = Instant::now();
                let excluded = clock.excluded();
                let mut changes = clock.changed.subscribe();
                loop {
                    let elapsed = start
                        .elapsed()
                        .saturating_sub(clock.excluded().saturating_sub(excluded));
                    let remaining = duration.saturating_sub(elapsed);
                    let paused = clock.state.lock().expect("network clock").paused.is_some();
                    tokio::select! {
                        biased;
                        result = &mut future => return Ok(result),
                        _ = changes.changed() => {},
                        _ = tokio::time::sleep(remaining), if !paused => {
                            if start.elapsed().saturating_sub(clock.excluded().saturating_sub(excluded)) >= duration { return Err(()); }
                        }
                    }
                }
            })
            .await
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    // D26: the Call snapshot's nested sign-in deadline must exclude a slow biometric,
    // while still enforcing its original remaining network budget afterwards.
    #[tokio::test(start_paused = true)]
    async fn nested_snapshot_budget_excludes_authorization_and_still_expires() {
        let task = tokio::spawn(timeout(Duration::from_secs(15), async {
            timeout(Duration::from_secs(20), async {
                tokio::time::sleep(Duration::from_secs(3)).await;
                authorization(tokio::time::sleep(Duration::from_secs(120))).await;
                tokio::time::sleep(Duration::from_secs(11)).await;
            })
            .await
            .unwrap();
            tokio::time::sleep(Duration::from_secs(2)).await;
        }));
        tokio::task::yield_now().await;
        tokio::time::advance(Duration::from_secs(3)).await;
        tokio::task::yield_now().await;
        tokio::time::advance(Duration::from_secs(119)).await;
        assert!(
            !task.is_finished(),
            "authentication must not consume the snapshot deadline"
        );
        tokio::time::advance(Duration::from_secs(1)).await;
        tokio::task::yield_now().await;
        tokio::time::advance(Duration::from_secs(11)).await;
        tokio::task::yield_now().await;
        assert!(!task.is_finished(), "14 seconds of network work still fit");
        tokio::time::advance(Duration::from_secs(1)).await;
        assert!(
            task.await.unwrap().is_err(),
            "the 15 second network budget remains enforced"
        );
    }
}
