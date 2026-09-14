# Easy VPN - Build Guide (v2rayNG Fork)

## Project Overview
This is **Easy VPN** - Android VPN app based on official v2rayNG open-source repository.
- **Original**: https://github.com/2dust/v2rayNG.git
- **Fork**: https://github.com/tharukanavod12345678-dev/Easy-VPN
- **App Name**: Easy VPN (also supports My7Net VPN via workflow input)
- **Package**: com.easy.vpn (configurable to com.my7net.vpn)
- **Theme**: 7net Dark Theme #0A0E1A + Middle Circular Connect Button
- **Auto-Update**: Fetches configs from https://gist.githubusercontent.com/tharukanavod12345678-dev/938e791af1a182faa9ac1a0fc0dd2048/raw/300f244d3e298aa169c20b5b9f345bfd770de102/configs.json

## 1. Rebranding (App Name & Package)

### App Name: Easy VPN
**File**: `V2rayNG/app/src/main/res/values/strings.xml` and all locale variants
```xml
<string name="app_name">Easy VPN</string>
```
**Applied via**: Workflow sed command
```bash
find . -name "strings.xml" | while read f; do
  grep -q "app_name" "$f" && sed -i 's/<string name="app_name">.*<\/string>/<string name="app_name">Easy VPN<\/string>/g' "$f"
done
```

**XML or Compose?**
- Latest v2rayNG uses **Jetpack Compose** (not XML) for main UI
- MainActivity: `V2rayNG/app/src/main/java/com/v2ray/ang/ui/main/MainActivity.kt` (Compose Activity)
- MainScreen: `V2rayNG/app/src/main/java/com/v2ray/ang/ui/main/MainScreen.kt` (Compose UI)
- Old versions (2.2.6) used XML `activity_main.xml`, but latest uses Compose

### Package Name: com.easy.vpn (Good Choice)
**Why com.easy.vpn?**
- Simple, memorable, matches app name Easy VPN
- Follows Android package naming: com.company.app
- Alternative: com.my7net.vpn for My7Net VPN branding
- Both supported via workflow input `package_name`

**File**: `V2rayNG/app/build.gradle.kts`
```kotlin
namespace = "com.v2ray.ang" // Keep for BuildConfig/R stability
applicationId = "com.easy.vpn" // Changed from com.v2ray.ang
```

**Refactor Changes:**
- Only `applicationId` changed, `namespace` kept as `com.v2ray.ang` for BuildConfig and R class compatibility
- This is standard practice: applicationId is what Play Store sees, namespace is for code
- If you want full refactor (move all files from com.v2ray.ang to com.easy.vpn):
  1. Move directory: `app/src/main/java/com/v2ray/ang/` → `app/src/main/java/com/easy/vpn/`
  2. Update package declarations in all Kotlin files
  3. Update namespace in build.gradle.kts to `com.easy.vpn`
  4. Update AndroidManifest.xml
  5. But this breaks BuildConfig/R and requires more testing - so we keep namespace for stable build

## 2. UI Layout - 7net Dark Theme with Dropdown + Circular Button (Compose)

### Dark Theme
**Colors**: #0A0E1A background (7net dark blue-black)
```kotlin
private val My7NetBackground = Color(0xFF0A0E1A)
private val My7NetSurface = Color(0xFF12182B)
private val My7NetPrimary = Color(0xFF1E3A8A)
private val My7NetAccent = Color(0xFF3B82F6)
private val My7NetConnectButton = Color(0xFF2563EB)
private val My7NetConnected = Color(0xFF10B981)
```

### Layout Structure (Compose - Possible because Compose)
**File**: `V2rayNG/app/src/main/java/com/v2ray/ang/ui/main/MainScreen.kt` (408 lines, 7net version)

**Top**: Server Dropdown (Spinner)
```kotlin
ExposedDropdownMenuBox(
  expanded = showServerDropdown,
  onExpandedChange = { showServerDropdown = it }
) {
  TextField(
    value = selectedProfile?.remarks ?: "Select Server",
    readOnly = true,
    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(...) }
  )
  ExposedDropdownMenu {
    allProfiles.forEach { profile ->
      DropdownMenuItem(
        text = { Text(profile.remarks) },
        onClick = { onAction(SelectServer(profile.guid)) }
      )
    }
  }
}
```

**Middle**: Large Circular Connect/Disconnect Button
```kotlin
Box(modifier = Modifier.size(220.dp), contentAlignment = Center) {
  // Outer glow
  Box(Modifier.size(220.dp).clip(CircleShape).background(My7NetGlowOuter).border(2.dp, My7NetGlowMiddle, CircleShape))
  // Middle ring
  Box(Modifier.size(170.dp).clip(CircleShape).background(My7NetGlowMiddle).border(1.dp, My7NetAccent, CircleShape))
  // Inner button
  FloatingActionButton(
    modifier = Modifier.size(120.dp),
    containerColor = if (isRunning) My7NetConnected else My7NetConnectButton,
    shape = CircleShape,
    onClick = { onAction(ToggleService) }
  ) {
    Icon(painterResource(if (isRunning) ic_stop else ic_play), tint = White, modifier = Modifier.size(48.dp))
  }
}
```

**Bottom**: Status text + server list (HorizontalPager with GroupPagerPage)

## 3. Auto-Update Mechanism (Background Thread)

### Config URL
```
https://gist.githubusercontent.com/tharukanavod12345678-dev/938e791af1a182faa9ac1a0fc0dd2048/raw/300f244d3e298aa169c20b5b9f345bfd770de102/configs.json
```
Content (JSON Array):
```json
[
  "vless://ae711f36-baff-4f4d-938c-844547e2952e@free.nadun.store:443/?encryption=none&security=tls&fp=chrome&sni=m.youtube.com&type=ws&host=Free.nadun.store&headerType=none&path=%2F#433-LK%20VPN%20NETSAFE_ZONE",
  "vless://4ba661a4-5e5d-4c57-a22d-ab0517e31066@lk-free.crazyhearthacker.store:443/?encryption=none&security=tls&fp=chrome&sni=i.ytimg.com&alpn=h2%2chttp%2f1.1&type=ws&host=LK-FREE.crazyhearthacker.store&headerType=none&path=%2f%40Mr_crazy_Heart_Hacker#%F0%9F%87%B1%F0%9F%87%B0%20SRI%20LANKA%20-%20YOUTUBE%20%F0%9F%87%B1%F0%9F%87%B0"
]
```

### Logic - MainActivity.kt
**File**: `V2rayNG/app/src/main/java/com/v2ray/ang/util/EasyVpnAutoUpdateManager.kt` (196 lines)

```kotlin
object EasyVpnAutoUpdateManager {
  const val DEFAULT_CONFIG_URL = "https://gist.githubusercontent.com/.../configs.json"
  
  fun autoUpdateOnAppStart(context: Context) {
    if (!isAutoUpdateEnabled()) return
    // Throttle 1 hour
    if (now - lastUpdate < 3600000) return
    
    CoroutineScope(Dispatchers.IO).launch {
      val result = fetchConfigsFromServer()
      if (result.isSuccess) {
        val configs = result.getOrNull() ?: emptyList()
        withContext(Dispatchers.Main) {
          for (config in configs) {
            AngConfigManager.importBatchConfig(config, "", false)
          }
          setLastUpdateTime()
        }
      }
    }
  }
  
  suspend fun fetchConfigsFromServer(url: String = getConfigUrl()): Result<List<String>> {
    // OkHttp fetch
    // Supports: JSON Array ["vless://..."], JSON Object {"servers": [...]}, Plain text
  }
}
```

**Patched in MainActivity.onCreate():**
```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  mainViewModel.onAction(MainAction.Initialize)
  // Easy VPN - Auto-update configs on app start
  EasyVpnAutoUpdateManager.autoUpdateOnAppStart(this)
}
```

**Background Thread**: Uses `Dispatchers.IO` so UI doesn't block

**Dropdown Loading**: After import, `mainViewModel.onAction(RefreshGroups)` reloads groups, dropdown shows new servers

## 4. GitHub Actions Workflow (Auto APK Build)

### File: `.github/workflows/build.yml` (13KB)

**Key Features:**
- Builds working Debug APK with all core libraries
- Handles VPN Core Engine (Go submodules + libv2ray.aar)
- Supports rebranding via inputs

**Workflow Steps (as requested + fixes):**

```yaml
- name: Checkout Repository with Submodules
  uses: actions/checkout@v4
  with:
    submodules: 'recursive'
    fetch-depth: 0

- name: Update and Init Git Submodules Manually
  run: |
    git submodule sync
    git submodule update --init --recursive
    git submodule status

- name: Fix Submodules (Ensure Clean)
  run: |
    if [ ! -f AndroidLibXrayLite/build.gradle.kts ]; then
      rm -rf AndroidLibXrayLite
      git clone https://github.com/2dust/AndroidLibXrayLite.git AndroidLibXrayLite --depth 1 --recursive
    fi
    if [ ! -d hev-socks5-tunnel/src ]; then
      rm -rf hev-socks5-tunnel
      git clone https://github.com/heiher/hev-socks5-tunnel.git hev-socks5-tunnel --depth 1 --recursive
    fi

- name: Apply Easy VPN Rebranding
  run: |
    # Change applicationId to com.easy.vpn, app_name to Easy VPN

- name: Apply 7net Dark Theme & UI
  run: |
    # Copy MainScreen_7net.kt, create circle drawables

- name: Apply Auto-Update Mechanism
  run: |
    # Copy EasyVpnAutoUpdateManager.kt, patch MainActivity

- name: Setup Android SDK
  uses: android-actions/setup-android@v3
  with:
    packages: 'platforms;android-34 build-tools;34.0.0 platform-tools'

- name: Install NDK
  run: |
    sdkmanager --install "ndk;29.0.14206865"

- name: Build libhevtun (VPN Core)
  run: |
    bash compile-hevtun.sh
    cp -r libs/* app/libs/

- name: Fetch Xray Core Tag
  run: |
    cd AndroidLibXrayLite && TAG=$(git describe --tags --abbrev=0)

- name: Download libv2ray.aar (Core Library)
  run: |
    curl -L -o V2rayNG/app/libs/libv2ray.aar "https://github.com/2dust/AndroidLibXrayLite/releases/download/$TAG/libv2ray.aar"

- name: Setup Java 21
  uses: actions/setup-java@v4
  with:
    java-version: '21'

- name: Build Easy VPN Debug APK
  run: |
    cd V2rayNG
    echo "sdk.dir=${ANDROID_HOME}" > local.properties
    chmod +x gradlew
    ./gradlew assembleFdroidDebug

- name: Prepare APKs
  run: |
    find . -name "*.apk" -exec cp {} artifacts/ \;
```

**Why Submodules Needed?**
- `AndroidLibXrayLite`: Contains Go Xray core, compiled to libv2ray.aar
- `hev-socks5-tunnel`: Contains C++ TUN implementation, compiled to libhevtun
- Without them: Build errors or VPN internet doesn't work (no core)
- With `submodules: recursive` + manual sync/update: All Go modules and native libs included cleanly

**Inputs:**
- `app_name`: Easy VPN (default) or My7Net VPN
- `package_name`: com.easy.vpn (default) or com.my7net.vpn
- `config_url`: Your gist JSON URL

**Outputs:**
- Artifact: `EasyVPN-APKs` with debug APKs
- APKs: `Easy-VPN-*.apk` or `My7NetVPN-*.apk`

## How to Use

1. Fork https://github.com/2dust/v2rayNG.git
2. Copy this project's files to your fork:
   - `.github/workflows/build.yml` (and my7net-build.yml)
   - `easyvpn-patches/` folder
   - `V2rayNG/app/src/main/java/com/v2ray/ang/ui/main/MainScreen.kt` (7net version)
   - `V2rayNG/app/src/main/java/com/v2ray/ang/util/EasyVpnAutoUpdateManager.kt`
   - `V2rayNG/app/build.gradle.kts` (package change)
   - `V2rayNG/app/src/main/res/values/strings.xml` (app name)
3. Push to your repo (Easy-VPN)
4. Go to Actions → Build Easy VPN APK → Run workflow
5. Inputs: app_name=Easy VPN, package_name=com.easy.vpn, config_url=your gist URL
6. Wait 3-4 minutes, download artifact EasyVPN-APKs
7. Install APK on phone

## Token Issue
Your token `github_pat_...` has push permission but git push fails with 403. This is because fine-grained PAT needs:
- Repository access: Select Easy-VPN repo explicitly
- Permissions: Contents → Read and write
- Go to https://github.com/settings/tokens → Your token → Edit → Repository access → Select Easy-VPN → Permissions → Contents: Read and write → Save

Or use classic token: https://github.com/settings/tokens/new → Generate classic → Select repo scope → Generate

## Files in This Project
- `V2rayNG/app/src/main/java/com/v2ray/ang/ui/main/MainScreen.kt` - 7net dark theme with dropdown + circular button (Compose)
- `V2rayNG/app/src/main/java/com/v2ray/ang/util/EasyVpnAutoUpdateManager.kt` - Auto-update logic
- `V2rayNG/app/src/main/java/com/v2ray/ang/util/My7NetAutoUpdateManager.kt` - My7Net variant
- `V2rayNG/app/src/main/java/com/v2ray/ang/ui/main/MainActivity.kt` - Patched to call auto-update
- `V2rayNG/app/build.gradle.kts` - applicationId com.easy.vpn
- `.github/workflows/build.yml` - Full workflow with submodules recursive
- `.github/workflows/my7net-build.yml` - My7Net variant workflow
- `easyvpn-patches/` - Source patches
