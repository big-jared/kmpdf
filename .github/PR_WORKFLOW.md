# Pull Request Workflow

This document describes the automated quality checks and PR workflow for KmPDF.

## Overview

All changes to the `main` branch must go through Pull Requests. Automated checks ensure code quality, API compatibility, and successful builds before merging.

## Automated Checks

When you open a PR, the following checks run automatically:

### 1. Code Quality (`detekt`)
- **What**: Static code analysis with Detekt
- **Checks**: Code smells, complexity, naming conventions, documentation
- **Required**: ✅ Yes
- **Fix**: Run `./gradlew detekt` locally and fix reported issues

### 2. API Compatibility (`api-check`)
- **What**: Binary compatibility validation
- **Checks**: Ensures API changes don't break binary compatibility
- **Required**: ✅ Yes
- **Fix**:
  - For intentional changes: `./gradlew apiDump` and commit `.api` files
  - For breaking changes: Document in PR description

### 3. Build (`build`)
- **What**: Full library build on macOS
- **Checks**: Compilation, tests, packaging
- **Required**: ✅ Yes
- **Fix**: Fix compilation/test errors shown in logs

### 4. Documentation (`build-docs`)
- **What**: Dokka API documentation generation
- **Checks**: KDoc comments are valid and complete
- **Required**: ✅ Yes
- **Fix**: Fix KDoc syntax errors or add missing documentation

## Creating a Pull Request

### 1. Create a Feature Branch

```bash
# From main branch
git checkout main
git pull origin main

# Create feature branch
git checkout -b feature/my-awesome-feature
```

### 2. Make Your Changes

```bash
# Edit files
# ...

# Run local checks before committing
./gradlew detekt
./gradlew apiCheck
./gradlew test

# Commit changes
git add .
git commit -m "feat: add awesome feature"
```

### 3. Push and Create PR

```bash
# Push branch
git push origin feature/my-awesome-feature

# Create PR via GitHub UI or CLI
gh pr create \
  --title "Add awesome feature" \
  --body "## Description

  This PR adds...

  ## Changes
  - Added X
  - Updated Y
  - Fixed Z

  ## Testing
  - [ ] Manual testing completed
  - [ ] Added/updated tests
  "
```

### 4. Wait for Checks

All 4 checks must pass:
- ✅ detekt
- ✅ api-check
- ✅ build
- ✅ build-docs

### 5. Request Review

Once checks pass, request review from a team member (if required by branch protection).

### 6. Address Feedback

```bash
# Make changes based on review
# ...

# Commit and push
git add .
git commit -m "fix: address review feedback"
git push origin feature/my-awesome-feature

# Checks will run again automatically
```

### 7. Merge

Once approved and all checks pass:

```bash
# Squash and merge (recommended)
gh pr merge --squash --delete-branch

# Or via GitHub UI
```

## Common Scenarios

### Detekt Failures

**Problem**: Detekt reports code quality issues

**Solution**:
```bash
# Run locally to see issues
./gradlew detekt

# View HTML report
open build/reports/detekt/detekt.html

# Fix issues and re-run
./gradlew detekt

# If you disagree with a rule, update config/detekt/detekt.yml
```

### API Check Failures

**Problem**: `apiCheck` fails with "API check failed for project"

**Solution**:

**For intentional API changes:**
```bash
# Update API signatures
./gradlew apiDump

# Commit the changes
git add kmpdf/api/*.api
git commit -m "chore: update API signatures"
git push
```

**For accidental breaking changes:**
- Revert the breaking change
- Or mark as breaking change in PR description and CHANGELOG

### Build Failures

**Problem**: Build fails on CI but works locally

**Common causes:**
1. **Platform-specific issues**: CI uses macOS for iOS/Android builds
2. **Missing dependencies**: Check that all dependencies are declared
3. **Test failures**: Tests might be flaky or environment-dependent

**Solution**:
```bash
# Run the exact CI command locally
./gradlew :kmpdf:build --no-configuration-cache

# Check CI logs for specific error
gh run view --log
```

### Documentation Failures

**Problem**: `build-docs` fails

**Common causes:**
1. Invalid KDoc syntax
2. Broken links in documentation
3. Missing KDoc for public API

**Solution**:
```bash
# Generate docs locally
./gradlew dokkaHtml

# View generated docs
open build/dokka/html/index.html

# Fix KDoc errors and re-run
```

## Pre-commit Checklist

Before creating a PR, run:

```bash
# Full quality check
./gradlew check

# Or run individually
./gradlew detekt        # Code quality
./gradlew apiCheck      # API compatibility
./gradlew test          # Tests
./gradlew dokkaHtml     # Documentation
./gradlew build         # Full build
```

## Commit Message Guidelines

Use conventional commits format:

```
<type>(<scope>): <subject>

<body>

<footer>
```

**Types:**
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation only
- `style`: Code style changes (formatting, etc)
- `refactor`: Code refactoring
- `perf`: Performance improvements
- `test`: Adding/updating tests
- `chore`: Build process, dependencies, etc
- `ci`: CI/CD changes

**Examples:**
```
feat(android): add auto-initialization via ContentProvider

Adds KmPdfInitializer ContentProvider that automatically initializes
the library on Android. Users no longer need to call initKmPdfGenerator()
manually.

Closes #123
```

```
fix(ios): correct file size calculation in PdfResult.Success

The file size was being calculated incorrectly on iOS. Now using
NSFileManager to get accurate file size.
```

## PR Template

When creating a PR, use this template:

```markdown
## Description
Brief description of the changes.

## Type of Change
- [ ] Bug fix (non-breaking change which fixes an issue)
- [ ] New feature (non-breaking change which adds functionality)
- [ ] Breaking change (fix or feature that would cause existing functionality to not work as expected)
- [ ] Documentation update

## Changes Made
- Change 1
- Change 2
- Change 3

## Testing
- [ ] Ran local tests (`./gradlew test`)
- [ ] Ran Detekt (`./gradlew detekt`)
- [ ] Ran API check (`./gradlew apiCheck`)
- [ ] Manual testing performed
- [ ] Added/updated tests

## API Changes
If this PR includes API changes:
- [ ] Updated API signatures (`./gradlew apiDump`)
- [ ] Added KDoc documentation
- [ ] Updated README if needed
- [ ] Marked as breaking change (if applicable)

## Checklist
- [ ] My code follows the style guidelines of this project
- [ ] I have performed a self-review of my own code
- [ ] I have commented my code, particularly in hard-to-understand areas
- [ ] I have made corresponding changes to the documentation
- [ ] My changes generate no new warnings
- [ ] I have added tests that prove my fix is effective or that my feature works
- [ ] New and existing unit tests pass locally with my changes
- [ ] Any dependent changes have been merged and published

## Screenshots (if applicable)
Add screenshots for UI changes.

## Related Issues
Closes #(issue number)
```

## Draft PRs

Use draft PRs for work in progress:

```bash
# Create draft PR
gh pr create --draft \
  --title "WIP: Add awesome feature" \
  --body "Work in progress. Feedback welcome!"

# Convert to ready for review when done
gh pr ready
```

**Benefits:**
- Triggers CI checks early
- Gets feedback before completion
- Shows progress to team
- Won't request reviews until ready

## Auto-merge

For PRs that pass all checks and are approved:

```bash
# Enable auto-merge (squash)
gh pr merge --auto --squash --delete-branch

# PR will auto-merge when:
# - All checks pass
# - Required approvals received
# - No merge conflicts
```

## Troubleshooting

### "Required checks not running"

1. Check Actions tab - are workflows enabled?
2. Check workflow files for syntax errors
3. Verify `on: pull_request` is configured correctly

### "Checks required but status not found"

The check name in branch protection must exactly match the job name:

```yaml
# In workflow file
jobs:
  detekt:  # ← This exact name
    runs-on: ubuntu-latest
```

### "Cannot merge - need approval"

If branch protection requires reviews:
1. Request review from team member
2. Wait for approval
3. Address any requested changes

### "Cannot merge - conflicts"

```bash
# Update your branch with main
git checkout feature/my-feature
git fetch origin
git rebase origin/main

# Resolve conflicts
# ...

# Force push (since rebase rewrites history)
git push --force-with-lease origin feature/my-feature
```

## Best Practices

1. **Keep PRs small**: Easier to review, faster to merge
2. **Write clear descriptions**: Explain what and why
3. **Run checks locally first**: Catch issues before CI
4. **Update documentation**: Keep docs in sync with code
5. **Add tests**: Ensure changes are tested
6. **Respond to reviews promptly**: Keep momentum
7. **Use draft PRs**: For early feedback
8. **Squash commits**: Keep history clean

## Resources

- [Code Quality Workflow](.github/workflows/code-quality.yml)
- [Build Workflow](.github/workflows/build.yml)
- [Detekt Configuration](../config/detekt/detekt.yml)
- [Contributing Guidelines](../CONTRIBUTING.md) (if exists)
- [GitHub Branch Protection Docs](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches)
