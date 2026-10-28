// Bundle the two ESM-only Markdown entry points for the existing CommonJS Jest runner.
// Tests exercise the actual renderer, including URL and HTML handling.
const { buildSync } = require('esbuild');
const { createHash } = require('node:crypto');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
module.exports = {
  process(_source, filename) {
    return { code: buildSync({ entryPoints: [filename], bundle: true, write: false, format: 'cjs', platform: 'node', external: ['react', 'react/jsx-runtime'] }).outputFiles[0].text };
  },
  getCacheKey(source, filename, options) {
    return createHash('sha256').update(source).update(filename).update(options.configString)
      .update(readFileSync(join(__dirname, '../package-lock.json'))).digest('hex');
  },
};
