# Release & Obtainium

Timber ships **APK** (not AAB) assets on GitHub Releases for [Obtainium](https://github.com/ImranR98/Obtainium).

Repo: https://github.com/zakjholt/timber  
Package: `dev.timber.app`  
Asset name: `timber-<versionName>-release.apk` (e.g. `timber-0.1.0-release.apk`)  
Tags: `vMAJOR.MINOR.PATCH` (e.g. `v0.1.0`)

## GitHub Actions secrets (required for signed Releases)

Create these under **Settings → Secrets and variables → Actions**:

| Secret | Value |
|--------|--------|
| `TIMBER_KEYSTORE_BASE64` | Base64 of the upload keystore file (`base64 -w0 timber-upload.jks`) |
| `TIMBER_KEYSTORE_PASSWORD` | Keystore password |
| `TIMBER_KEY_ALIAS` | Key alias (e.g. `timber`) |
| `TIMBER_KEY_PASSWORD` | Key password |

Without these secrets, `Release APK` still runs `assembleRelease` and uploads an **unsigned** workflow artifact (CI check only). It will **not** create a GitHub Release until signing secrets are present.

## Generate & back up the upload keystore (once)

Do this **before** the first keepable install. Losing this keystore means Obtainium/sideload updates cannot replace the installed app (same `applicationId` requires the same signing key).

```bash
keytool -genkeypair -v \
  -keystore timber-upload.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias timber \
  -storepass 'CHOOSE_A_STRONG_STORE_PASS' \
  -keypass 'CHOOSE_A_STRONG_KEY_PASS' \
  -dname "CN=Timber, OU=Mobile, O=Zak Holt, L=Unknown, ST=Unknown, C=US"

# Encode for the GitHub secret (macOS: omit -w0 or use `base64 | tr -d '\n'`)
base64 -w0 timber-upload.jks > timber-upload.jks.b64
```

- Store `timber-upload.jks` + passwords in offline backup (password manager + encrypted drive).
- Never commit the `.jks` or `.b64` file to git (see `.gitignore`).

## First release flow

1. Merge the release CI PR to `main`.
2. Create the four `TIMBER_*` secrets above.
3. Ensure `main` is green / what you want to ship.
4. Tag and push:

```bash
git checkout main
git pull origin main
git tag v0.1.0
git push origin v0.1.0
```

5. Watch **Actions → Release APK**. On success it creates Release `v0.1.0` with `timber-0.1.0-release.apk`.
6. Install via Obtainium (see README) or download the APK from the Release page.

`versionCode` for tag builds is derived as `MAJOR*10000 + MINOR*100 + PATCH` (so `v0.1.0` → `100`). Bump semver on every publish so both Obtainium and Android see a newer build.

### Manual / dry-run

**Actions → Release APK → Run workflow** (`workflow_dispatch`) accepts `version` + `version_code` and uploads a workflow artifact only (no GitHub Release unless you also push a `v*` tag).

## Local signed assemble (optional)

```bash
export TIMBER_KEYSTORE_FILE=/absolute/path/to/timber-upload.jks
export TIMBER_KEYSTORE_PASSWORD=...
export TIMBER_KEY_ALIAS=timber
export TIMBER_KEY_PASSWORD=...
./gradlew :app:assembleRelease -PVERSION_NAME=0.1.0 -PVERSION_CODE=100
# APK under app/build/outputs/apk/release/
```
