// Prepara el proyecto Android generado por Capacitor: icono, pantalla de inicio,
// permisos, número de versión, firma y datos para el aviso de actualizaciones.
// Lo ejecuta GitHub Actions; no hace falta tocarlo.
import fs from 'node:fs';
import path from 'node:path';

const root = process.cwd();
const app = path.join(root, 'android', 'app');
const res = path.join(app, 'src', 'main', 'res');
if (!fs.existsSync(app)) { console.error('No existe android/app. Ejecuta antes: npx cap add android'); process.exit(1); }

// 1) Icono, icono adaptativo/temático y pantalla de inicio
const copyDir = (from, to) => {
  for (const e of fs.readdirSync(from, { withFileTypes: true })) {
    const a = path.join(from, e.name), b = path.join(to, e.name);
    if (e.isDirectory()) { fs.mkdirSync(b, { recursive: true }); copyDir(a, b); } else fs.copyFileSync(a, b);
  }
};
copyDir(path.join(root, 'android-res'), res);
for (const f of ['drawable-v24/ic_launcher_foreground.xml', 'drawable/ic_launcher_background.xml']) {
  const p = path.join(res, f); if (fs.existsSync(p)) fs.rmSync(p); // los sustituyen los PNG de mipmap
}

// 2) Tema oscuro en el arranque
const stylesPath = path.join(res, 'values', 'styles.xml');
let styles = fs.readFileSync(stylesPath, 'utf8');
if (!styles.includes('windowSplashScreenBackground')) {
  styles = styles.replace(
    /(<style name="AppTheme\.NoActionBarLaunch"[^>]*>)/,
    `$1\n        <item name="windowSplashScreenBackground">@color/orbita_bg</item>\n        <item name="windowSplashScreenAnimatedIcon">@mipmap/ic_launcher_foreground</item>\n        <item name="android:windowBackground">@color/orbita_bg</item>`
  );
  styles = styles.replace(
    /(<style name="AppTheme\.NoActionBar"[^>]*>)/,
    `$1\n        <item name="android:windowBackground">@color/orbita_bg</item>`
  );
  fs.writeFileSync(stylesPath, styles);
}

// 3) Permisos: micrófono, reproducción en segundo plano con controles
const manifestPath = path.join(app, 'src', 'main', 'AndroidManifest.xml');
let manifest = fs.readFileSync(manifestPath, 'utf8');
const perms = [
  'android.permission.READ_MEDIA_AUDIO',
  'android.permission.RECORD_AUDIO',
  'android.permission.MODIFY_AUDIO_SETTINGS',
  'android.permission.FOREGROUND_SERVICE',
  'android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK',
  'android.permission.WAKE_LOCK'
];
for (const p of perms) {
  if (!manifest.includes(`"${p}"`)) manifest = manifest.replace('</manifest>', `    <uses-permission android:name="${p}" />\n</manifest>`);
}
if (!manifest.includes('"android.permission.READ_EXTERNAL_STORAGE"')) {
  manifest = manifest.replace('</manifest>', '    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />\n</manifest>');
}
if (!manifest.includes('android:hardwareAccelerated')) manifest = manifest.replace('<application', '<application\n        android:hardwareAccelerated="true"');
// Radios de internet que emiten por http (sin cifrar)
if (!manifest.includes('android:usesCleartextTraffic')) manifest = manifest.replace('<application', '<application\n        android:usesCleartextTraffic="true"');

// EXTRAS=0 es el plan B del workflow: la app sin widget ni «Compartir con Órbita», con el módulo nativo de la 1.1
const extras = process.env.EXTRAS !== '0';
if (extras) {
  // «Compartir › Órbita» desde YouTube, WhatsApp, el gestor de archivos…
  const share = `
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="text/plain" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <action android:name="android.intent.action.SEND_MULTIPLE" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="audio/*" />
            </intent-filter>
`;
  if (!manifest.includes('android.intent.action.SEND')) {
    const m = manifest.match(/<activity[\s\S]*?android:name="\.MainActivity"[\s\S]*?<\/activity>/) || manifest.match(/<activity[\s\S]*?<\/activity>/);
    if (m) manifest = manifest.replace(m[0], m[0].replace(/<\/activity>$/, share + '        </activity>'));
    else console.warn('No encuentro la actividad principal: sin «Compartir con Órbita»');
  }
  // Widget de la pantalla de inicio
  if (!manifest.includes('.OrbitaWidget')) {
    manifest = manifest.replace('</application>', `    <receiver
            android:name=".OrbitaWidget"
            android:exported="false"
            android:label="@string/orbita_widget_label">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data
                android:name="android.appwidget.provider"
                android:resource="@xml/orbita_widget_info" />
        </receiver>
    </application>`);
  }
}
// El motor de sonido de Android (3.5): su servicio de reproducción (el aviso con los botones y que Android no lo
// congele con la pantalla apagada). NATIVEAUDIO=0 es un respaldo del workflow: la app sin él.
const nativeAudio = extras && process.env.NATIVEAUDIO !== '0' && fs.existsSync(path.join(root, 'android-audio'));
// 4.2 · El reproductor en Kotlin con Media3 (carpeta android-player). PLAYER=java es un respaldo del workflow: el de la 4.1.
const kotlinPlayer = nativeAudio && process.env.PLAYER !== 'java' && fs.existsSync(path.join(root, 'android-player'));
// 4.3 · Las pantallas nativas (carpeta android-ui, Kotlin con Compose). NATIVEUI=0 es un respaldo del workflow: sin ellas.
const nativeUi = kotlinPlayer && process.env.NATIVEUI !== '0' && fs.existsSync(path.join(root, 'android-ui'));
if (kotlinPlayer && !manifest.includes('.OrbitaService')) {
  // el servicio de música (Media3) y los botones de los auriculares con Órbita cerrada
  manifest = manifest.replace('</application>', `    <service android:name=".OrbitaService" android:exported="true" android:foregroundServiceType="mediaPlayback">
            <intent-filter>
                <action android:name="androidx.media3.session.MediaSessionService" />
            </intent-filter>
        </service>
        <receiver android:name=".OrbitaButtonReceiver" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MEDIA_BUTTON" />
            </intent-filter>
        </receiver>
        <receiver android:name="androidx.media.session.MediaButtonReceiver" tools:node="remove" />
    </application>`);
  // 4.2 b2 · Media3 exige UN solo receptor de botones de auriculares en la app: se quita el del plugin antiguo
  // (con los dos, el servicio de música fallaba al crearse y la app se cerraba)
  if (!manifest.includes('xmlns:tools')) manifest = manifest.replace('<manifest', '<manifest xmlns:tools="http://schemas.android.com/tools"');
} else if (nativeAudio && !kotlinPlayer && !manifest.includes('.NovaMediaService')) {
  manifest = manifest.replace('</application>', `    <service android:name=".NovaMediaService" android:exported="false" android:foregroundServiceType="mediaPlayback" />
    </application>`);
}
// El despertador con Nova (3.5). ALARM=0 es un respaldo del workflow: la app sin él.
const alarm = extras && process.env.ALARM !== '0' && fs.existsSync(path.join(root, 'android-alarm'));
if (alarm) {
  for (const p of ['android.permission.USE_EXACT_ALARM', 'android.permission.USE_FULL_SCREEN_INTENT', 'android.permission.POST_NOTIFICATIONS', 'android.permission.RECEIVE_BOOT_COMPLETED', 'android.permission.VIBRATE']) {
    if (!manifest.includes(`"${p}"`)) manifest = manifest.replace('</manifest>', `    <uses-permission android:name="${p}" />\n</manifest>`);
  }
  if (!manifest.includes('"android.permission.SCHEDULE_EXACT_ALARM"')) {
    manifest = manifest.replace('</manifest>', '    <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" android:maxSdkVersion="32" />\n</manifest>');
  }
  if (!manifest.includes('.NovaAlarmReceiver')) {
    manifest = manifest.replace('</application>', `    <receiver android:name=".NovaAlarmReceiver" android:exported="false" />
        <receiver android:name=".NovaAlarmBoot" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
                <action android:name="android.intent.action.TIME_SET" />
                <action android:name="android.intent.action.TIMEZONE_CHANGED" />
                <action android:name="android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" />
            </intent-filter>
        </receiver>
    </application>`);
  }
}
fs.writeFileSync(manifestPath, manifest);

// 3b) Módulo nativo: música del móvil (MediaStore, sin copiarla), widget, copias de seguridad y compartir
const appId = JSON.parse(fs.readFileSync(path.join(root, 'capacitor.config.json'), 'utf8')).appId;
const javaDir = path.join(app, 'src', 'main', 'java', ...appId.split('.'));
fs.mkdirSync(javaDir, { recursive: true });
const javaFrom = [path.join(root, extras ? 'android-src' : 'android-safe')];
if (extras) javaFrom.push(path.join(root, 'android-extras', 'java'));
for (const dir of javaFrom) {
  for (const f of fs.readdirSync(dir)) {
    const code = fs.readFileSync(path.join(dir, f), 'utf8').replace(/__PACKAGE__/g, appId);
    fs.writeFileSync(path.join(javaDir, f), code);
  }
}
if (extras) copyDir(path.join(root, 'android-extras', 'res'), res);

// 3c) El motor de sonido de Android (3.5, beta). NATIVEAUDIO=0 es un respaldo del workflow: la app sin él.
if (nativeAudio && kotlinPlayer) {
  // 4.2: el motor (android-audio/NovaAudioEngine.java, el mismo de siempre) y el reproductor nuevo (android-player)
  const put = (dir, f) => fs.writeFileSync(path.join(javaDir, f), fs.readFileSync(path.join(root, dir, f), 'utf8').replace(/__PACKAGE__/g, appId));
  put('android-audio', 'NovaAudioEngine.java');
  for (const f of fs.readdirSync(path.join(root, 'android-player')).filter(f => f.endsWith('.java') || f.endsWith('.kt'))) put('android-player', f);
  console.log('Motor de sonido de Android: incluido, con el reproductor de Kotlin (Media3)');
  // 4.3 · Fase 2: las pantallas nativas (Kotlin y Compose) y sus tipos de letra
  if (nativeUi) {
    for (const f of fs.readdirSync(path.join(root, 'android-ui')).filter(f => f.endsWith('.kt'))) put('android-ui', f);
    if (fs.existsSync(path.join(root, 'android-ui', 'res'))) copyDir(path.join(root, 'android-ui', 'res'), res);
    console.log('Pantallas nativas (Compose): incluidas');
  }
} else if (nativeAudio) {
  for (const f of fs.readdirSync(path.join(root, 'android-audio')).filter(f => f.endsWith('.java'))) {
    fs.writeFileSync(path.join(javaDir, f), fs.readFileSync(path.join(root, 'android-audio', f), 'utf8').replace(/__PACKAGE__/g, appId));
  }
  console.log('Motor de sonido de Android: incluido' + (process.env.PLAYER === 'java' ? ' (con el reproductor de la 4.1: compilación de respaldo)' : ''));
} else if (extras) console.log('Motor de sonido de Android: no incluido' + (process.env.NATIVEAUDIO === '0' ? ' (compilación de respaldo)' : ''));

// 3d) El despertador con Nova (3.5): su código, su icono del aviso (los permisos y receptores ya están en el manifiesto)
if (alarm) {
  for (const f of fs.readdirSync(path.join(root, 'android-alarm')).filter(f => f.endsWith('.java'))) {
    fs.writeFileSync(path.join(javaDir, f), fs.readFileSync(path.join(root, 'android-alarm', f), 'utf8').replace(/__PACKAGE__/g, appId));
  }
  if (fs.existsSync(path.join(root, 'android-alarm', 'res'))) copyDir(path.join(root, 'android-alarm', 'res'), res);
  console.log('Despertador: incluido');
} else if (extras) console.log('Despertador: no incluido' + (process.env.ALARM === '0' ? ' (compilación de respaldo)' : ''));

// 4) Versión: <mayor>.<menor>.<número de compilación>
const pkg = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
const build = parseInt(process.env.BUILD_NUMBER || '1', 10);
const [maj, min] = String(pkg.version || '1.0.0').split('.');
const version = `${maj || 1}.${min || 0}.${build}`;
// "beta": true en package.json → se publica como versión de prueba: no les llega el aviso a los usuarios
const beta = pkg.beta === true;
const gradlePath = path.join(app, 'build.gradle');
let gradle = fs.readFileSync(gradlePath, 'utf8');
gradle = gradle.replace(/versionCode\s+\d+/, `versionCode ${build}`).replace(/versionName\s+"[^"]*"/, `versionName "${version}"`);

// 4a) El cerebro grande de Nova (llama.cpp, solo móviles ARM de 64 bits). Necesita el código de llama.cpp
//     (LLAMA_DIR, lo descarga el workflow). BIGBRAIN=0 es el respaldo: la app sin el cerebro grande.
const llamaDir = process.env.LLAMA_DIR || '';
const bigBrain = extras && process.env.BIGBRAIN !== '0' && llamaDir && fs.existsSync(path.join(llamaDir, 'CMakeLists.txt'));
if (bigBrain) {
  const cppDir = path.join(app, 'src', 'main', 'cpp');
  fs.mkdirSync(cppDir, { recursive: true });
  for (const f of fs.readdirSync(path.join(root, 'android-brain', 'cpp'))) fs.copyFileSync(path.join(root, 'android-brain', 'cpp', f), path.join(cppDir, f));
  const novaDir = path.join(app, 'src', 'main', 'java', 'orbita', 'nova');
  fs.mkdirSync(novaDir, { recursive: true });
  fs.copyFileSync(path.join(root, 'android-brain', 'java', 'NovaLlm.java'), path.join(novaDir, 'NovaLlm.java'));
  fs.writeFileSync(path.join(javaDir, 'NovaBrainPlugin.java'), fs.readFileSync(path.join(root, 'android-brain', 'NovaBrainPlugin.java'), 'utf8').replace(/__PACKAGE__/g, appId));
  // NDK: Gradle exige que ndkVersion y ndkPath coincidan. Se elige uno instalado (mejor un 27, el que espera
  // Gradle; si no, el estable más reciente) y se le dice a Gradle su versión exacta.
  const ndkRev = d => { try { const m = fs.readFileSync(path.join(d, 'source.properties'), 'utf8').match(/Pkg\.Revision\s*=\s*([^\s]+)/); return m ? m[1] : ''; } catch { return ''; } };
  const ndkCands = new Map();
  const addNdk = d => { if (d && fs.existsSync(d)) { const r = ndkRev(d); if (r) ndkCands.set(fs.realpathSync(d), r); } };
  for (const sdk of [process.env.ANDROID_HOME, process.env.ANDROID_SDK_ROOT]) {
    const nd = sdk && path.join(sdk, 'ndk');
    if (nd && fs.existsSync(nd)) for (const v of fs.readdirSync(nd)) addNdk(path.join(nd, v));
  }
  for (const e of [process.env.ANDROID_NDK_LATEST_HOME, process.env.ANDROID_NDK_HOME, process.env.ANDROID_NDK_ROOT]) addNdk(e);
  const major = r => parseInt(r, 10) || 0;
  const cmpRev = (a, b) => { const x = a.split(/[.-]/).map(n => parseInt(n, 10) || 0), y = b.split(/[.-]/).map(n => parseInt(n, 10) || 0); for (let i = 0; i < 4; i++) if ((x[i] || 0) !== (y[i] || 0)) return (x[i] || 0) - (y[i] || 0); return 0; };
  const stable = [...ndkCands].filter(([, r]) => /^\d+\.\d+\.\d+$/.test(r) && major(r) >= 26);
  const pickFrom = list => list.sort((a, b) => cmpRev(b[1], a[1]))[0];
  const chosen = pickFrom(stable.filter(([, r]) => major(r) === 27)) || pickFrom(stable.filter(([, r]) => major(r) <= 29)) || pickFrom(stable) || pickFrom([...ndkCands]);
  const ndk = chosen ? chosen[0] : '', ndkVer = chosen ? chosen[1] : '';
  const q = v => JSON.stringify(v).replace(/\$/g, '\\$');
  gradle += `
android {
    defaultConfig {
        externalNativeBuild {
            cmake {
                arguments ${q('-DLLAMA_DIR=' + llamaDir)}, "-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16", "-DCMAKE_BUILD_TYPE=Release"
                abiFilters 'arm64-v8a'
            }
        }
    }
    externalNativeBuild {
        cmake {
            path "src/main/cpp/CMakeLists.txt"
        }
    }${ndk ? `
    ndkVersion ${q(ndkVer)}
    ndkPath ${q(ndk)}` : ''}
}
`;
  console.log('Cerebro grande: incluido (llama.cpp de ' + llamaDir + (ndk ? ', NDK ' + ndkVer + ' en ' + ndk + ' · instalados: ' + [...ndkCands.values()].join(', ') : ', sin NDK localizado') + ')');
} else if (extras) console.log('Cerebro grande: no incluido' + (process.env.BIGBRAIN === '0' ? ' (compilación de respaldo)' : ' (falta LLAMA_DIR)'));

// 4b) Motor nativo de la voz propia de Nova (ONNX Runtime para Android): habla antes y fuera del WebView.
//     En el plan B (EXTRAS=0) no se incluye y la voz usa el motor web, como en la beta 2.
if (extras && !gradle.includes('onnxruntime-android')) {
  gradle = gradle.replace(/dependencies\s*\{/, m => `${m}\n    implementation 'com.microsoft.onnxruntime:onnxruntime-android:1.20.0'`);
  gradle += `
android {
    defaultConfig {
        ndk {
            abiFilters 'arm64-v8a', 'armeabi-v7a', 'x86_64'
        }
    }
}
`;
}

// 5) Firma: siempre la misma clave, para poder instalar las actualizaciones encima
if (process.env.KEYSTORE_BASE64) {
  fs.writeFileSync(path.join(app, 'orbita.keystore'), Buffer.from(process.env.KEYSTORE_BASE64.replace(/\s+/g, ''), 'base64'));
  if (!gradle.includes('signingConfigs.orbita')) {
    gradle += `
android {
    signingConfigs {
        orbita {
            storeFile = file('orbita.keystore')
            storePassword = System.getenv('KEYSTORE_PASSWORD')
            keyAlias = 'orbita'
            keyPassword = System.getenv('KEYSTORE_PASSWORD')
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.orbita
        }
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
`;
  }
}
// 4c) 4.2 · Kotlin y Media3 para el reproductor nuevo (las versiones que usa Capacitor 8 y su Android Gradle Plugin)
if (kotlinPlayer) {
  const rootGradlePath = path.join(root, 'android', 'build.gradle');
  let rootGradle = fs.readFileSync(rootGradlePath, 'utf8');
  if (!rootGradle.includes('kotlin-gradle-plugin')) {
    rootGradle = rootGradle.replace(/(classpath\s+['"]com\.android\.tools\.build:gradle:[^'"]+['"])/, `$1\n        classpath 'org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.20'`);
    if (!rootGradle.includes('kotlin-gradle-plugin')) throw new Error('No encuentro dónde añadir Kotlin en android/build.gradle');
    fs.writeFileSync(rootGradlePath, rootGradle);
  }
  if (!gradle.includes("apply plugin: 'kotlin-android'")) gradle = gradle.replace(/apply plugin:\s*'com\.android\.application'/, m => `${m}\napply plugin: 'kotlin-android'`);
  if (!gradle.includes('media3-session')) {
    gradle = gradle.replace(/dependencies\s*\{/, m => `${m}\n    implementation 'androidx.media3:media3-session:1.9.4'\n    implementation 'androidx.media3:media3-common:1.9.4'`);
    gradle += `
// Kotlin compila para la misma versión de Java que el resto de la app (Capacitor usa Java 21)
tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile).configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
`;
  }
  const propsPath = path.join(root, 'android', 'gradle.properties');
  let props = fs.existsSync(propsPath) ? fs.readFileSync(propsPath, 'utf8') : '';
  if (!props.includes('kotlin.jvm.target.validation.mode')) fs.writeFileSync(propsPath, props.replace(/\n*$/, '\n') + 'kotlin.jvm.target.validation.mode=warning\n');
  console.log('Reproductor de Kotlin: Kotlin 2.2.20 y Media3 1.9.4');
  // 4.3 · Compose para las pantallas nativas
  if (nativeUi && !gradle.includes('compose-bom')) {
    let rg = fs.readFileSync(rootGradlePath, 'utf8');
    if (!rg.includes('compose-compiler-gradle-plugin')) {
      rg = rg.replace(/(classpath\s+'org\.jetbrains\.kotlin:kotlin-gradle-plugin:[^']+')/, `$1\n        classpath 'org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.2.20'`);
      if (!rg.includes('compose-compiler-gradle-plugin')) throw new Error('No encuentro dónde añadir Compose en android/build.gradle');
      fs.writeFileSync(rootGradlePath, rg);
    }
    gradle = gradle.replace(/apply plugin:\s*'kotlin-android'/, m => `${m}\napply plugin: 'org.jetbrains.kotlin.plugin.compose'`);
    gradle = gradle.replace(/dependencies\s*\{/, m => `${m}\n    implementation platform('androidx.compose:compose-bom:2025.09.00')\n    implementation 'androidx.compose.ui:ui'\n    implementation 'androidx.compose.foundation:foundation'`);
    gradle += `
android {
    buildFeatures {
        compose true
    }
}
`;
    console.log('Pantallas nativas: Compose (BOM 2025.09.00)');
  }
}
fs.writeFileSync(gradlePath, gradle);

// 6) Datos para el aviso de actualizaciones dentro de la app
const repo = process.env.REPO || '';
fs.writeFileSync(path.join(root, 'www', 'app-config.js'),
  `window.ORBITA_CONFIG = ${JSON.stringify({ repo, version, build, beta })};\n`);

// Salidas para los siguientes pasos del workflow
if (process.env.GITHUB_OUTPUT) fs.appendFileSync(process.env.GITHUB_OUTPUT, `version=${version}\nprerelease=${beta}\n`);
console.log(`Órbita ${version}${beta ? ' (beta)' : ''} preparada (repo: ${repo || 'sin configurar'}${extras ? '' : ', modo seguro sin widget'})`);
