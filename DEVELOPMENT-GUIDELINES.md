# Development Guidelines

## Commit Message Format

When making commits, always follow up with a clear list of which files need to be updated in Hubitat.

### Template

After each commit, provide:

```
## Commit [commit-hash]
**Files Changed:**
- ✅ **`KiaUVODriver.groovy`** - Update this driver in Hubitat
- ✅ **`KiaUVOApp.groovy`** - Update this app in Hubitat  
- ✅ **`OpenEVSEDriver.groovy`** - Update this driver in Hubitat
- ✅ **`KiaClimateSeatDriver.groovy`** - Update this child driver in Hubitat
- ℹ️ **`README.md`** - Documentation only, no Hubitat update needed
- ℹ️ **`DASHBOARD-SETUP.md`** - Documentation only, no Hubitat update needed
```

### Hubitat Files to Watch

Files that require Hubitat updates when changed:
- `KiaUVODriver.groovy` - Main vehicle driver
- `KiaUVOApp.groovy` - Parent app for authentication
- `OpenEVSEDriver.groovy` - OpenEVSE charging station driver
- `KiaClimateSeatDriver.groovy` - Child driver for seat controls

Files that don't require Hubitat updates:
- `*.md` files (documentation)
- `debug/*` files (development/testing only)
- `.gitignore`, `.pre-commit-config.yaml`, etc.
- CSS files (used in dashboards, not in drivers)

## Testing Workflow

1. Make changes to driver/app files
2. Commit changes
3. Push to GitHub
4. List affected Hubitat files for user
5. User updates those specific files in their Hubitat hub
6. User tests the changes

## Important Notes

- Always specify which exact files need to be updated in Hubitat
- User must manually copy/paste updated code into Hubitat web interface
- Changes don't take effect until user clicks "Save" in Hubitat
- Some changes may require clicking "Initialize" or "Configure" commands after update

