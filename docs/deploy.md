# Deploying IERailMetrics to the VPS

IERailMetrics runs on the VPS as a **systemd service** running a single Spring Boot jar, behind the web server that
already terminates HTTPS for https://ierailmetrics.com. Every commit that passes the `build` workflow on `main` is
deployed automatically.

```
push to main ─► build (verify, Testcontainers) ─► deploy workflow
                                                     │ mvn package on the runner
                                                     ▼
                    ssh (restricted key) ─ jar on stdin ─► /opt/ierailmetrics/deploy.sh
                                                     │ releases/ierailmetrics-<sha>.jar, current.jar -> release
                                                     │ systemctl restart ierailmetrics
                                                     │ GET /actuator/health (rolls back if it never answers)
                                                     ▼
                                          smoke test: https://ierailmetrics.com/overview
```

The server never clones the repository and needs no Maven: the jar is built and tested on the GitHub runner and
streamed in. Releases are kept in `releases/` (the newest 5), so a rollback is just re-pointing `current.jar`.

## One-time server setup

**Shortcut:** `deploy/setup-vps.sh` does steps 1 to 4 below in one go (it finds the service running the jar today,
repoints it at `current.jar` through a systemd drop-in, installs the script and authorizes the key; if the service
does not come back healthy it restores the original unit):

```bash
scp deploy/deploy.sh deploy/setup-vps.sh root@<vps>:/tmp/
ssh root@<vps> 'bash /tmp/setup-vps.sh "ssh-ed25519 AAAA... ierailmetrics-deploy"'   # SERVICE=<unit> if it cannot guess
```

The manual steps are kept for reference.

Everything below runs as root on the VPS. Names and paths are the defaults of `deploy/deploy.sh`; override them with
`APP_DIR`, `SERVICE` and `HEALTH_URL`; if yours differ (another service name or port), edit those defaults at the top
of the installed copy.

1. **Directory and first release.** Move the jar that is running today under the new layout:

   ```bash
   mkdir -p /opt/ierailmetrics/releases
   cp /path/to/the/current/irishrail.jar /opt/ierailmetrics/releases/ierailmetrics-initial.jar
   ln -sfn /opt/ierailmetrics/releases/ierailmetrics-initial.jar /opt/ierailmetrics/current.jar
   ```

2. **Service.** Point the existing unit's `ExecStart` at `current.jar` (keep your `EnvironmentFile`/`Environment` lines
   for `DB_PASSWORD`, the Spring profile and so on). A minimal unit looks like:

   ```ini
   # /etc/systemd/system/ierailmetrics.service
   [Unit]
   Description=IERailMetrics
   After=network.target postgresql.service

   [Service]
   EnvironmentFile=/etc/ierailmetrics.env        # DB_USERNAME, DB_PASSWORD, SPRING_PROFILES_ACTIVE=prod
   ExecStart=/usr/bin/java -jar /opt/ierailmetrics/current.jar
   Restart=on-failure
   SuccessExitStatus=143

   [Install]
   WantedBy=multi-user.target
   ```

   Then `systemctl daemon-reload && systemctl restart ierailmetrics` and check
   `curl -s 127.0.0.1:8080/actuator/health` returns `{"status":"UP"}`. If the app listens on another port, change
   `HEALTH_URL` in the installed script. The service must be able to read `current.jar` (releases are written as mode
   640 owned by root; run the service as root, as the current setup does, or `chgrp` the releases to the service group).

3. **Deploy script.** Install it from the repository (repeat this step whenever `deploy/deploy.sh` changes; the
   workflow ships only the jar):

   ```bash
   install -m 755 deploy/deploy.sh /opt/ierailmetrics/deploy.sh
   touch /var/log/ierailmetrics-deploy.log
   ```

4. **Deploy key.** Create a key pair **dedicated to this project** (on your machine):

   ```bash
   ssh-keygen -t ed25519 -N '' -C 'ierailmetrics-deploy' -f ierailmetrics_deploy
   ```

   Add the public key to `/root/.ssh/authorized_keys` as a *forced command* so the key can do nothing else:

   ```
   command="/opt/ierailmetrics/deploy.sh",no-pty,no-port-forwarding,no-agent-forwarding,no-X11-forwarding ssh-ed25519 AAAA... ierailmetrics-deploy
   ```

   > The techrat.io key cannot simply be reused: `sshd` applies the options of the first `authorized_keys` line that
   > matches a key, so one key can only be bound to one forced command. A second, dedicated key also means a leak of
   > one project's secret does not give access to the other.

5. **GitHub secrets** (Settings → Secrets and variables → Actions, or `gh secret set`):

   | Secret | Value |
   |---|---|
   | `VPS_HOST` | server IP or hostname |
   | `VPS_SSH_KEY` | contents of the private key `ierailmetrics_deploy` |
   | `VPS_KNOWN_HOSTS` | output of `ssh-keyscan <host>` |

   Optionally create the `production` environment (Settings → Environments) to add required reviewers.

6. **First run.** Actions → *deploy* → *Run workflow*, or just merge to `main`.

## What a deploy does and when it fails

- The `deploy` workflow starts only when `build` succeeds on a push to `main`. It builds the jar for exactly that
  commit and skips itself if `main` has already moved on (the newer commit will have its own run).
- On the server, `deploy.sh` checks the upload is a jar, takes a lock (one deploy at a time), switches `current.jar`,
  restarts the service and polls `/actuator/health` (up to ~2 minutes). If it never answers, the previous release is
  restored and restarted and the workflow fails, so the site stays on the last good version.
- The log is `/var/log/ierailmetrics-deploy.log`. Secrets stay in the service's environment file and are never
  touched by a deploy.

Roll back by hand: `ln -sfn /opt/ierailmetrics/releases/<older>.jar /opt/ierailmetrics/current.jar && systemctl restart ierailmetrics`.

## Tests

`bash deploy/test_deploy.sh` exercises `deploy.sh` against a throwaway directory with fake `systemctl` and `curl`:
successful deploy, rollback when unhealthy, pruning, rejection of non-jar input and of a malformed sha. It also runs in
the `build` workflow.
