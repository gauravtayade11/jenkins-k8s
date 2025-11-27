# Contributing & Git Flow

This project follows **GitFlow** branching strategy for organized and collaborative development.

## Branch Structure

```
main (production-ready)
  │
  └── develop (integration branch)
        │
        ├── feature/xxx (new features)
        ├── bugfix/xxx (bug fixes)
        └── release/x.x.x (release preparation)
```

## Branch Naming Conventions

| Branch Type | Pattern | Example |
|-------------|---------|---------|
| Main | `main` | `main` |
| Develop | `develop` | `develop` |
| Feature | `feature/<description>` | `feature/add-sonarqube-integration` |
| Bugfix | `bugfix/<description>` | `bugfix/fix-agent-memory-leak` |
| Release | `release/<version>` | `release/1.0.0` |
| Hotfix | `hotfix/<description>` | `hotfix/critical-security-patch` |

## Workflow

### Starting a New Feature

```bash
# Make sure you're on develop
git checkout develop
git pull origin develop

# Create feature branch
git checkout -b feature/your-feature-name

# Work on your feature...
git add .
git commit -m "feat: add your feature description"

# Push feature branch
git push origin feature/your-feature-name
```

### Completing a Feature

```bash
# Update develop branch
git checkout develop
git pull origin develop

# Merge feature branch
git merge feature/your-feature-name

# Delete feature branch
git branch -d feature/your-feature-name
git push origin --delete feature/your-feature-name

# Push develop
git push origin develop
```

### Creating a Release

```bash
# Create release branch from develop
git checkout develop
git checkout -b release/1.0.0

# Bump version, final testing, documentation updates...
git commit -m "chore: prepare release 1.0.0"

# Merge to main
git checkout main
git merge release/1.0.0
git tag -a v1.0.0 -m "Release version 1.0.0"

# Merge back to develop
git checkout develop
git merge release/1.0.0

# Push everything
git push origin main develop --tags

# Delete release branch
git branch -d release/1.0.0
```

## Commit Message Convention

We follow **Conventional Commits**:

```
<type>(<scope>): <description>

[optional body]

[optional footer]
```

### Types

| Type | Description |
|------|-------------|
| `feat` | New feature |
| `fix` | Bug fix |
| `docs` | Documentation only |
| `style` | Formatting, missing semicolons, etc. |
| `refactor` | Code change that neither fixes a bug nor adds a feature |
| `perf` | Performance improvement |
| `test` | Adding tests |
| `chore` | Build process or auxiliary tool changes |

### Examples

```bash
feat(jenkins): add prometheus metrics endpoint
fix(agents): resolve memory leak in maven pod template
docs(part-2): add troubleshooting section for RBAC
chore(k8s): update resource limits for production
```

## Project Milestones

| Version | Description | Status |
|---------|-------------|--------|
| v0.1.0 | Initial setup with basic Jenkins deployment | In Progress |
| v0.2.0 | Add security configurations (RBAC, Network Policies) | Planned |
| v0.3.0 | Dynamic pod agents configuration | Planned |
| v0.4.0 | Spring Boot sample project integration | Planned |
| v1.0.0 | Production-ready release | Planned |

## Code Review Guidelines

1. All changes must go through pull requests
2. At least one approval required before merging
3. All CI checks must pass
4. Documentation must be updated for new features
5. Security implications must be considered and documented
