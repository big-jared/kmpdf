#!/bin/bash

# KmPDF Release Script
# Creates a new release with version bump and changelog generation

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Function to print colored output
print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

# Check if git working directory is clean
check_git_status() {
    if [[ -n $(git status -s) ]]; then
        print_error "Working directory is not clean. Please commit or stash your changes."
        exit 1
    fi
}

# Get current version from build.gradle.kts
get_current_version() {
    grep "^val libraryVersion = " kmpdf/build.gradle.kts | sed 's/val libraryVersion = "\(.*\)"/\1/'
}

# Update version in build.gradle.kts
update_version() {
    local new_version=$1
    sed -i.bak "s/^val libraryVersion = .*/val libraryVersion = \"$new_version\"/" kmpdf/build.gradle.kts
    rm kmpdf/build.gradle.kts.bak
    print_info "Updated version to $new_version in kmpdf/build.gradle.kts"
}

# Generate changelog from git log
generate_changelog() {
    local last_tag=$1
    # new_tag parameter kept for future use
    # local new_tag=$2

    print_info "Generating changelog from $last_tag to HEAD..."

    echo "## What's Changed"
    echo ""

    # Get merged PRs
    if git log "$last_tag"..HEAD --merges --pretty=format:"%s" | grep -q "Merge pull request"; then
        echo "### Pull Requests"
        git log "$last_tag"..HEAD --merges --pretty=format:"* %s @%an" | grep "Merge pull request" | sed 's/Merge pull request //' | sed 's/ from .*//'
        echo ""
    fi

    # Get commits (excluding merges)
    echo "### Commits"
    git log "$last_tag"..HEAD --no-merges --pretty=format:"* %s (%h)" --reverse
    echo ""
    echo ""

    # Get contributors
    echo "### Contributors"
    git log "$last_tag"..HEAD --pretty=format:"* @%an" | sort -u
    echo ""
}

# Main release flow
main() {
    print_info "Starting KmPDF release process..."

    # Check git status
    check_git_status

    # Run quality checks
    print_info "Running quality checks..."

    print_info "Running Detekt..."
    ./gradlew detekt || {
        print_error "Detekt checks failed. Please fix issues before releasing."
        exit 1
    }

    print_info "Running binary compatibility check..."
    ./gradlew apiCheck || {
        print_error "Binary compatibility check failed. Run './gradlew apiDump' to update API signatures."
        exit 1
    }

    print_info "Running tests..."
    ./gradlew test || {
        print_error "Tests failed. Please fix before releasing."
        exit 1
    }

    print_info "Generating documentation with Dokka..."
    ./gradlew dokkaHtml || {
        print_warning "Documentation generation failed, but continuing..."
    }

    print_info "✅ All checks passed!"

    # Get current version
    current_version=$(get_current_version)
    print_info "Current version: $current_version"

    # Get new version from user
    echo ""
    read -r -p "Enter new version (e.g., 1.0.1, 1.1.0, 2.0.0): " new_version

    if [[ ! $new_version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
        print_error "Invalid version format. Use semantic versioning (e.g., 1.0.0)"
        exit 1
    fi

    # Confirm version bump
    echo ""
    print_warning "Version will be updated from $current_version to $new_version"
    read -p "Continue? (y/n): " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        print_error "Release cancelled"
        exit 1
    fi

    # Update version in build.gradle.kts
    update_version "$new_version"

    # Get last tag for changelog
    last_tag=$(git describe --tags --abbrev=0 2>/dev/null || echo "")
    if [[ -z $last_tag ]]; then
        last_tag=$(git rev-list --max-parents=0 HEAD)
        print_warning "No previous tags found, using initial commit"
    fi

    # Generate changelog
    changelog_file="CHANGELOG_v${new_version}.md"
    generate_changelog "$last_tag" "v${new_version}" > "$changelog_file"
    print_info "Generated changelog: $changelog_file"

    # Show changelog
    echo ""
    print_info "Changelog preview:"
    cat "$changelog_file"
    echo ""

    # Commit version bump
    git add kmpdf/build.gradle.kts
    git commit -m "chore: bump version to $new_version"
    print_info "Committed version bump"

    # Create and push tag
    tag_name="v${new_version}"
    git tag -a "$tag_name" -m "Release $tag_name"
    print_info "Created tag: $tag_name"

    echo ""
    print_warning "Ready to push release. This will:"
    print_warning "  1. Push version bump commit to main"
    print_warning "  2. Push tag $tag_name"
    print_warning "  3. Trigger GitHub Actions to publish to Maven Central"
    echo ""
    read -p "Push to remote? (y/n): " -n 1 -r
    echo

    if [[ $REPLY =~ ^[Yy]$ ]]; then
        git push origin main
        git push origin "$tag_name"
        print_info "Pushed to remote!"
        echo ""
        print_info "🎉 Release $tag_name initiated!"
        print_info "📝 Changelog saved to $changelog_file"
        print_info "🔗 View progress at: https://github.com/big-jared/kmpdf/actions"
        print_info "📦 Once published, update GitHub release notes with changelog from $changelog_file"
    else
        print_warning "Push cancelled. To push manually, run:"
        print_warning "  git push origin main"
        print_warning "  git push origin $tag_name"
    fi
}

# Run main
main
