// Ersetzt die Capacitor-Standard-Icons durch das Narrhalla-Icon
// und setzt die Versionsnummer (BUILD_NUMBER), damit Updates installierbar sind.
import { copyFileSync, readdirSync, rmSync, readFileSync, writeFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const res = 'android/app/src/main/res';
for (const dir of readdirSync(res).filter((d) => d.startsWith('mipmap-'))) {
  if (dir.includes('anydpi')) { rmSync(join(res, dir), { recursive: true }); continue; }
  for (const name of ['ic_launcher.png', 'ic_launcher_round.png']) copyFileSync('icons/icon-192.png', join(res, dir, name));
  const fg = join(res, dir, 'ic_launcher_foreground.png');
  if (existsSync(fg)) rmSync(fg);
}

const build = process.env.BUILD_NUMBER;
if (build) {
  const gradle = 'android/app/build.gradle';
  writeFileSync(gradle, readFileSync(gradle, 'utf8')
    .replace(/versionCode \d+/, `versionCode ${build}`)
    .replace(/versionName "[^"]*"/, `versionName "0.1.${build}"`));
}
console.log('Android-Icons und Version gesetzt');
