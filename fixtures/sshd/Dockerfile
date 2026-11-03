# A real OpenSSH sshd for SSH-path tests. herdr-core is bind-mounted at
# /usr/local/bin/herdr-core by the test harness (crates/test-support).
# Users: aries and noisy (key auth only); bootstrap (password allowed, no
# ~/.ssh yet) and locked (password allowed, root-owned ~/.ssh) for the
# password bootstrap tests.
FROM alpine:3.20

RUN apk add --no-cache openssh-server git \
 && ssh-keygen -A \
 && printf '%s\n' \
      'PasswordAuthentication no' \
      'KbdInteractiveAuthentication no' \
      'PubkeyAuthentication yes' \
      'AllowTcpForwarding yes' \
      'Match User bootstrap,locked' \
      '    PasswordAuthentication yes' \
      > /etc/ssh/sshd_config.d/aries-test.conf \
 && printf '#!/bin/sh\necho "Welcome to the noisy Host"\nprintf "last login: never "\nexec /bin/sh "$@"\n' \
      > /usr/local/bin/noisy-shell \
 && chmod 755 /usr/local/bin/noisy-shell \
 && echo /usr/local/bin/noisy-shell >> /etc/shells \
 && adduser -D -s /bin/sh aries \
 && adduser -D -s /usr/local/bin/noisy-shell noisy \
 && for u in aries noisy; do \
      sed -i "s/^$u:!/$u:*/" /etc/shadow; \
      mkdir -p /home/$u/.ssh && touch /home/$u/.ssh/authorized_keys; \
      chown -R $u:$u /home/$u/.ssh; chmod 700 /home/$u/.ssh; chmod 600 /home/$u/.ssh/authorized_keys; \
    done \
 && adduser -D -s /bin/sh bootstrap \
 && adduser -D -s /bin/sh locked \
 && printf '%s\n' 'bootstrap:aries-test-password' 'locked:aries-test-password' | chpasswd \
 && mkdir -p /home/locked/.ssh && touch /home/locked/.ssh/authorized_keys \
 && chown -R root:root /home/locked/.ssh && chmod 755 /home/locked/.ssh && chmod 644 /home/locked/.ssh/authorized_keys

EXPOSE 22
# Loop so a test can rotate the host keys and restart sshd in place.
CMD ["/bin/sh", "-c", "while true; do /usr/sbin/sshd -D -e; sleep 0.1; done"]
