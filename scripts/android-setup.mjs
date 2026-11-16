// Passt das von Capacitor erzeugte Android-Projekt an:
// - Narrhalla-Icon statt Capacitor-Standard
// - Versionsnummer aus BUILD_NUMBER, damit Updates installierbar sind
// - Mikrofon-Berechtigung (eigene Zählstimme)
// - Release-Build mit festem Schlüssel aus dem Repo (release.keystore), damit
//   neue Versionen sich über die alte installieren lassen, ohne Daten zu verlieren
import { copyFileSync, readdirSync, rmSync, readFileSync, writeFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const res = 'android/app/src/main/res';
for (const dir of readdirSync(res).filter((d) => d.startsWith('mipmap-'))) {
  if (dir.includes('anydpi')) { rmSync(join(res, dir), { recursive: true }); continue; }
  for (const name of ['ic_launcher.png', 'ic_launcher_round.png']) copyFileSync('icons/icon-192.png', join(res, dir, name));
  const fg = join(res, dir, 'ic_launcher_foreground.png');
  if (existsSync(fg)) rmSync(fg);
}

const gradleFile = 'android/app/build.gradle';
let gradle = readFileSync(gradleFile, 'utf8');

const build = process.env.BUILD_NUMBER;
if (build) {
  gradle = gradle
    .replace(/versionCode \d+/, `versionCode ${build}`)
    .replace(/versionName "[^"]*"/, `versionName "0.1.${build}"`);
}

if (!gradle.includes('signingConfigs')) {
  gradle = gradle.replace(/buildTypes \{\s*release \{/, `signingConfigs {
        release {
            storeFile rootProject.file('../release.keystore')
            storePassword 'narrhalla'
            keyAlias 'narrhalla'
            keyPassword 'narrhalla'
            enableV1Signing true
            enableV2Signing true
            enableV3Signing true
        }
    }
    buildTypes {
        release {
            signingConfig signingConfigs.release`);
}
writeFileSync(gradleFile, gradle);

// Mikrofon für „eigene Zählstimme aufnehmen“
const manifestFile = 'android/app/src/main/AndroidManifest.xml';
let manifest = readFileSync(manifestFile, 'utf8');
for (const perm of ['android.permission.RECORD_AUDIO', 'android.permission.MODIFY_AUDIO_SETTINGS']) {
  if (!manifest.includes(perm)) manifest = manifest.replace('</manifest>', `    <uses-permission android:name="${perm}" />\n</manifest>`);
}
writeFileSync(manifestFile, manifest);
console.log('Android-Projekt angepasst (Icons, Version, Signatur)');
