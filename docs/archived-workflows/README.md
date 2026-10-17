# Archived CI/CD — ChipTrip Backend

On 2026-10-18, the previous VPS deployment was retired. The former GitHub Actions workflow was moved from `.github/workflows/ci-cd.yml` to `docs/archived-workflows/ci-cd.yml` so it cannot run on pushes or pull requests.

## Restore later

1. Provision the replacement deployment environment and verify host, app service, JAR location, ports and health checks.
2. Configure fresh GitHub Actions secrets on the repository for that environment. Avoid reusing credentials of the retired VPS.
3. Review and update `docs/archived-workflows/ci-cd.yml`, especially the VPS deployment steps and health check.
4. Move the reviewed file back to `.github/workflows/ci-cd.yml` in a separate pull request, and run a safe build before enabling automatic deployment.

Do not deploy the archived workflow unchanged to a new host.
