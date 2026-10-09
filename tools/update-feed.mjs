// Serves a stand-in for GitHub's release list, for rehearsing an update on the emulator
// (docs/testing.md, "Rehearsing an update"; decisions §36). Nothing is published.
//
//   node tools/update-feed.mjs <apk> <version> [port]
//
// The emulator reaches this PC at 10.0.2.2, so the APK's URL uses that address. The list has the
// same shape as GitHub's: a desktop release marked Latest that the phone must skip, and the Android
// release with its size and sha256 digest.
import { createServer } from 'node:http'
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { basename } from 'node:path'

const [apkPath, version, port = '8765'] = process.argv.slice(2)
if (!apkPath || !version) {
  console.error('Usage: node tools/update-feed.mjs <apk> <version> [port]')
  process.exit(1)
}
const apk = readFileSync(apkPath)
const name = `HonoKit-${version}.apk`
const base = `http://10.0.2.2:${port}`
const releases = [
  {
    tag_name: 'v0.17.0',
    draft: false,
    prerelease: false,
    html_url: `${base}/v0.17.0`,
    assets: [{ name: 'HonoKit-Setup-0.17.0.exe', size: 1, browser_download_url: `${base}/none.exe` }],
  },
  {
    tag_name: `android-v${version}`,
    draft: false,
    prerelease: false,
    html_url: `${base}/android-v${version}`,
    assets: [
      {
        name,
        size: apk.length,
        digest: `sha256:${createHash('sha256').update(apk).digest('hex')}`,
        browser_download_url: `${base}/${name}`,
      },
    ],
  },
]

createServer((req, res) => {
  console.log(new Date().toISOString().slice(11, 19), req.method, req.url)
  if (req.url.startsWith('/releases.json')) {
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify(releases))
  } else if (req.url === `/${name}`) {
    res.writeHead(200, { 'Content-Type': 'application/vnd.android.package-archive', 'Content-Length': apk.length })
    res.end(apk)
  } else {
    res.writeHead(404)
    res.end()
  }
}).listen(Number(port), () => console.log(`Serving ${basename(apkPath)} as ${name} (android-v${version}) on ${base}/releases.json`))
