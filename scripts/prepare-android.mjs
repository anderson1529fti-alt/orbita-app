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
fs.writeFileSync(manifestPath, manifest);

// 3b) Módulo nativo que lee la música del móvil (MediaStore) sin copiarla
const appId = JSON.parse(fs.readFileSync(path.join(root, 'capacitor.config.json'), 'utf8')).appId;
const javaDir = path.join(app, 'src', 'main', 'java', ...appId.split('.'));
fs.mkdirSync(javaDir, { recursive: true });
for (const f of fs.readdirSync(path.join(root, 'android-src'))) {
  const code = fs.readFileSync(path.join(root, 'android-src', f), 'utf8').replace(/__PACKAGE__/g, appId);
  fs.writeFileSync(path.join(javaDir, f), code);
}

// 4) Versión: 1.0.<número de compilación>
const pkg = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
const build = parseInt(process.env.BUILD_NUMBER || '1', 10);
const [maj, min] = String(pkg.version || '1.0.0').split('.');
const version = `${maj || 1}.${min || 0}.${build}`;
const gradlePath = path.join(app, 'build.gradle');
let gradle = fs.readFileSync(gradlePath, 'utf8');
gradle = gradle.replace(/versionCode\s+\d+/, `versionCode ${build}`).replace(/versionName\s+"[^"]*"/, `versionName "${version}"`);

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
  `window.ORBITA_CONFIG = ${JSON.stringify({ repo, version, build })};\n`);

// Salidas para los siguientes pasos del workflow
if (process.env.GITHUB_OUTPUT) fs.appendFileSync(process.env.GITHUB_OUTPUT, `version=${version}\n`);
console.log(`Órbita ${version} preparada (repo: ${repo || 'sin configurar'})`);
