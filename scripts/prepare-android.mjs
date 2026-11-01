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
fs.writeFileSync(gradlePath, gradle);

// 6) Datos para el aviso de actualizaciones dentro de la app
const repo = process.env.REPO || '';
fs.writeFileSync(path.join(root, 'www', 'app-config.js'),
  `window.ORBITA_CONFIG = ${JSON.stringify({ repo, version, build, beta })};\n`);

// Salidas para los siguientes pasos del workflow
if (process.env.GITHUB_OUTPUT) fs.appendFileSync(process.env.GITHUB_OUTPUT, `version=${version}\nprerelease=${beta}\n`);
console.log(`Órbita ${version}${beta ? ' (beta)' : ''} preparada (repo: ${repo || 'sin configurar'}${extras ? '' : ', modo seguro sin widget'})`);
