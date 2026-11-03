# A Linux Host with systemd as PID 1 and a real OpenSSH sshd, for the Beacon
# install tests: an SSH login gets a logind session and a systemd user
# manager, so `systemctl --user` and `loginctl enable-linger` behave as on a
# real Host. herdr-core is bind-mounted at /usr/local/bin/herdr-core by the
# test harness (crates/test-support, `SystemdHost`).
FROM debian:trixie-slim

ENV container=docker
RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      systemd systemd-sysv dbus dbus-user-session libpam-systemd openssh-server \
 && rm -rf /var/lib/apt/lists/* \
 && printf '%s\n' \
      'PasswordAuthentication no' \
      'KbdInteractiveAuthentication no' \
      'PubkeyAuthentication yes' \
      'UsePAM yes' \
      > /etc/ssh/sshd_config.d/aries-test.conf \
 && systemctl enable ssh.service \
 && systemctl mask getty@tty1.service console-getty.service systemd-firstboot.service \
 && useradd -m -s /bin/sh aries \
 && sed -i 's/^aries:!/aries:*/' /etc/shadow \
 && mkdir -p /home/aries/.ssh && touch /home/aries/.ssh/authorized_keys \
 && chown -R aries:aries /home/aries/.ssh \
 && chmod 700 /home/aries/.ssh && chmod 600 /home/aries/.ssh/authorized_keys

EXPOSE 22
STOPSIGNAL SIGRTMIN+3
CMD ["/sbin/init"]
