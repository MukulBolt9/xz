# ─────────────────────────────────────────────────────────────────────────────
# NowBrief Beta Upload Script
# Tag: v2.0.8-beta.8  →  triggers .github/workflows/beta-release.yml
#
# WORKFLOW SUMMARY (from beta-release.yml):
#   trigger  : push tag matching v*-beta*
#   builds   : AAB (bundleRelease) → bundletool → universal APK
#   signs    : uses KEYSTORE_BASE64 / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD secrets
#   publishes: pre-release on GitHub as "NowBrief v2.0.8-beta.8 (Beta)"
#   artifact : NowBrief-2.0.8-beta.8-beta-universal.apk + AAB
#
# PRE-REQUISITES:
#   1. git installed and on PATH
#   2. You are inside the repo root (where gradlew lives)
#   3. GitHub remote is set to https://github.com/MukulBolt9/xz
#   4. All secrets already set in repo Settings → Secrets:
#        KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD
# ─────────────────────────────────────────────────────────────────────────────

$ErrorActionPreference = "Stop"

# ── Config ────────────────────────────────────────────────────────────────────
$TAG         = "v2.0.8-beta.8"
$BRANCH      = "main"          # branch to tag off
$REMOTE      = "origin"
$REPO        = "MukulBolt9/xz"

# ── Step 1: Safety check — are we inside the repo? ───────────────────────────
if (-not (Test-Path ".git")) {
    Write-Error "ERROR: Run this script from the repo root (where .git folder is)."
    exit 1
}

# ── Step 2: Make sure we're on the right branch and up to date ───────────────
Write-Host "`n[1/6] Checking out $BRANCH and pulling latest..." -ForegroundColor Cyan
git checkout $BRANCH
git pull $REMOTE $BRANCH

# ── Step 3: Delete existing local tag if it exists ───────────────────────────
Write-Host "`n[2/6] Checking for existing local tag $TAG..." -ForegroundColor Cyan
$existingTag = git tag -l $TAG
if ($existingTag) {
    Write-Host "  → Deleting existing local tag $TAG" -ForegroundColor Yellow
    git tag -d $TAG
}

# ── Step 4: Delete existing remote tag if it exists ──────────────────────────
Write-Host "`n[3/6] Checking for existing remote tag $TAG..." -ForegroundColor Cyan
$remoteTags = git ls-remote --tags $REMOTE "refs/tags/$TAG"
if ($remoteTags) {
    Write-Host "  → Deleting existing remote tag $TAG" -ForegroundColor Yellow
    git push $REMOTE ":refs/tags/$TAG"
}

# ── Step 5: Create annotated tag ─────────────────────────────────────────────
Write-Host "`n[4/6] Creating annotated tag $TAG..." -ForegroundColor Cyan
$commitMsg = "NowBrief $TAG

- Perfect circle rings (Steps, Screen, Sleep 2-in-1)
- NavBar + search bar above Samsung navigation buttons (navigationBarsPadding)
- All 3 rings equal weight/size
- HC connected state persists across restarts
- Detailed bridge app setup guide in Health Connect panel
"
git tag -a $TAG -m $commitMsg
Write-Host "  → Tag created: $TAG" -ForegroundColor Green

# ── Step 6: Push tag to remote (triggers beta-release.yml) ───────────────────
Write-Host "`n[5/6] Pushing tag $TAG to $REMOTE → triggers GitHub Actions..." -ForegroundColor Cyan
git push $REMOTE $TAG

Write-Host "`n[6/6] Done!" -ForegroundColor Green
Write-Host ""
Write-Host "═══════════════════════════════════════════════════════" -ForegroundColor Magenta
Write-Host "  Tag pushed: $TAG" -ForegroundColor White
Write-Host "  Workflow  : beta-release.yml (triggered automatically)" -ForegroundColor White
Write-Host "  Builds    : AAB + universal APK (signed)" -ForegroundColor White
Write-Host "  Release   : https://github.com/$REPO/releases/tag/$TAG" -ForegroundColor White
Write-Host "  Actions   : https://github.com/$REPO/actions" -ForegroundColor White
Write-Host "═══════════════════════════════════════════════════════" -ForegroundColor Magenta
Write-Host ""
Write-Host "Monitor the build at the Actions URL above." -ForegroundColor DarkGray
Write-Host "Once complete, the APK will appear at the Release URL." -ForegroundColor DarkGray
