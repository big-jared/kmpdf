# PR Enforcement Setup - Complete

This document summarizes the PR enforcement setup for KmPDF.

## What Was Configured

### 1. ✅ GitHub Actions Workflows

#### Code Quality Workflow (`.github/workflows/code-quality.yml`)
Runs on every PR to `main`:
- **detekt**: Static code analysis
- **api-check**: Binary compatibility validation
- **build-docs**: API documentation generation

#### Build Workflow (`.github/workflows/build.yml`)
Runs on every PR to `main`:
- **build**: Full library build and tests on macOS

### 2. ✅ Documentation

#### Branch Protection Guide (`.github/BRANCH_PROTECTION.md`)
- Detailed instructions for setting up branch protection via UI, CLI, or automation
- Explains all protection settings
- Includes troubleshooting guide

#### PR Workflow Guide (`.github/PR_WORKFLOW.md`)
- Complete PR workflow documentation
- Common scenarios and solutions
- Commit message guidelines
- Pre-commit checklist
- PR template

#### Setup Script (`scripts/setup-branch-protection.sh`)
- Automated script to configure branch protection via GitHub CLI
- Interactive prompts
- Error handling

## Branch Protection Configuration

Branch protection is **already enabled** on the `main` branch with:
- ✅ Require PRs for all changes
- ✅ Require 1 approval
- ✅ Require status checks: `detekt`, `api-check`, `build`, `build-docs`
- ✅ Require conversation resolution
- ✅ Require linear history
- ✅ Enforce for administrators
- ✅ Block force pushes and deletions

To modify these settings, go to **Repository Settings** → **Branches** → Edit the `main` protection rule.

## Workflow After Setup

### Developer Workflow

```bash
# 1. Create feature branch
git checkout -b feature/awesome-feature

# 2. Make changes and commit
git add .
git commit -m "feat: add awesome feature"

# 3. Run local checks (optional but recommended)
./gradlew detekt apiCheck test

# 4. Push and create PR
git push origin feature/awesome-feature
gh pr create --title "Add awesome feature" --body "Description"

# 5. Wait for CI checks to pass
# - detekt ✅
# - api-check ✅
# - build ✅
# - build-docs ✅

# 6. Request review (if required)

# 7. Merge after approval and checks pass
gh pr merge --squash --delete-branch
```

### Direct Push to Main - BLOCKED ❌

```bash
git checkout main
git commit -m "quick fix"
git push origin main
# ❌ ERROR: GH006: Protected branch update failed
```

## CI/CD Checks

### What Gets Checked on Every PR

| Check | Tool | What It Does | Fix Command |
|-------|------|--------------|-------------|
| **detekt** | Detekt | Code quality, style, complexity | `./gradlew detekt` |
| **api-check** | Binary Compatibility Validator | API stability | `./gradlew apiDump` |
| **build** | Gradle | Compilation, tests | `./gradlew build` |
| **build-docs** | Dokka | Documentation generation | `./gradlew dokkaHtml` |

### Local Pre-commit Check

Run before creating a PR:

```bash
# Full check
./gradlew check

# Or individually
./gradlew detekt        # Code quality
./gradlew apiCheck      # API compatibility
./gradlew test          # Run tests
./gradlew dokkaHtml     # Generate docs
```

## Files Created/Modified

### New Files
- `.github/workflows/code-quality.yml` - Quality checks workflow
- `.github/PR_WORKFLOW.md` - PR workflow documentation
- `PR_ENFORCEMENT_SETUP.md` - This file

### Modified Files
- `.github/workflows/build.yml` - Updated to trigger on `main` PRs only

## Testing the Setup

### 1. Verify Workflows Run

```bash
# Create a test branch
git checkout -b test/ci-check

# Make a change
echo "# Test" >> TEST.md
git add TEST.md
git commit -m "test: verify CI"

# Push and create PR
git push origin test/ci-check
gh pr create --title "Test CI" --body "Testing workflows"

# Check Actions tab - should see:
# - Code Quality workflow running
# - Build Library workflow running
```

### 2. Verify Branch Protection (After Setup)

```bash
# Try to push directly to main
git checkout main
echo "test" >> README.md
git add README.md
git commit -m "test"
git push origin main
# Should see: ERROR: GH006: Protected branch update failed
```

### 3. Test Detekt Check

```bash
# Create branch with intentional violation
git checkout -b test/detekt-fail

# Add file with bad code
cat > test.kt << 'EOF'
package test

fun badFunction() {
    // TODO: this should fail
    println("test")
}
EOF

git add test.kt
git commit -m "test: detekt violation"
git push origin test/detekt-fail
gh pr create --title "Test Detekt" --body "Should fail detekt"

# Check PR - detekt check should fail ❌
```

## Enforcement Levels

With branch protection enabled:

### ✅ ALLOWED
- Creating PRs
- Pushing to feature branches
- Running workflows
- Closing PRs without merging

### ❌ BLOCKED
- Direct commits to main
- Force pushes to main
- Deleting main branch
- Merging PRs without approvals (if configured)
- Merging PRs with failing checks
- Merging PRs with unresolved conversations

## Common Scenarios

### Scenario 1: Detekt Failure

```bash
# PR fails detekt check
./gradlew detekt  # Run locally
# Fix issues
git commit -am "fix: detekt violations"
git push  # Triggers checks again
```

### Scenario 2: API Breaking Change

```bash
# PR fails api-check
./gradlew apiDump  # Update signatures
git add kmpdf/api/*.api
git commit -m "chore: update API signatures"
git push
```

### Scenario 3: Need Emergency Fix

If you absolutely must bypass (not recommended):

1. Temporarily disable branch protection
2. Make the fix
3. Re-enable protection immediately
4. Create follow-up PR for proper review

## Benefits

### For Code Quality
- ✅ All code reviewed before merge
- ✅ Automated style enforcement
- ✅ API stability guaranteed
- ✅ Documentation kept up-to-date

### For Team
- ✅ Clear contribution process
- ✅ Consistent code standards
- ✅ Reduced bugs in main
- ✅ Better collaboration

### For Project
- ✅ Professional workflow
- ✅ Audit trail of changes
- ✅ Easier rollbacks
- ✅ Maintainable codebase

## Troubleshooting

### "Workflow not running"
- Check `.github/workflows/` files exist
- Verify workflows are enabled in repo settings
- Check Actions tab for errors

### "Checks required but not found"
- Check job names match exactly
- Wait a few minutes for GitHub to sync
- Re-run workflows if needed

### "Cannot enable branch protection"
- Requires admin permissions
- Use GitHub CLI with proper auth
- Check organization settings

## Next Steps

1. **Enable branch protection**: Run `./scripts/setup-branch-protection.sh`
2. **Test the workflow**: Create a test PR
3. **Update team**: Share `.github/PR_WORKFLOW.md` with contributors
4. **Add to README**: Link to PR workflow documentation

## Resources

- [PR Workflow Guide](.github/PR_WORKFLOW.md)
- [Code Quality Workflow](.github/workflows/code-quality.yml)
- [Build Workflow](.github/workflows/build.yml)
- [GitHub Branch Protection Docs](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches)

## Summary

✅ **GitHub Actions workflows created** for automated checks
✅ **Documentation complete** for setup and usage
✅ **Branch protection ACTIVE** on `main` branch
✅ **All references to `develop` branch removed**

**Branch protection is now enabled!** All commits to `main` must go through PRs with passing checks! 🎉
