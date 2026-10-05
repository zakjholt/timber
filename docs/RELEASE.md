# Releases

Signed APKs on GitHub Releases for Obtainium. Stable tags only: `vMAJOR.MINOR.PATCH`.  
Asset: `timber-<version>-release.apk` · package `dev.timber.app`

## Secrets

Repo → Settings → Secrets and variables → Actions:

| Secret | Value |
|--------|--------|
| `TIMBER_KEYSTORE_BASE64` | `base64 -w0 timber-upload.jks` |
| `TIMBER_KEYSTORE_PASSWORD` | keystore password |
| `TIMBER_KEY_ALIAS` | e.g. `timber` |
| `TIMBER_KEY_PASSWORD` | key password |

Without secrets, CI builds an unsigned artifact only and skips the GitHub Release.

## Keystore (once, before first keepable install)

Same signing key is required for every sideload update. Back up the `.jks` + passwords offline; do not commit them.

```bash
keytool -genkeypair -v \
  -keystore timber-upload.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias timber \
  -storepass 'STORE_PASS' \
  -keypass 'KEY_PASS' \
  -dname "CN=Timber, O=Zak Holt, C=US"

base64 -w0 timber-upload.jks   # paste into TIMBER_KEYSTORE_BASE64
```

## Ship

```bash
git checkout main && git pull
git tag v0.1.0
git push origin v0.1.0
```

`versionCode` = `MAJOR*10000 + MINOR*100 + PATCH` (`v0.1.0` → `100`).  
`workflow_dispatch` builds an artifact only (no Release).
