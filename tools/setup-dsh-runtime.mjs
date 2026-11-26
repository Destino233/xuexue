import { spawnSync } from 'node:child_process';
import { chmodSync, copyFileSync, existsSync, realpathSync, statSync } from 'node:fs';
import { homedir } from 'node:os';
import { delimiter, dirname, resolve } from 'node:path';

const [major, minor] = process.versions.node.split('.').map(Number);
if (major < 24 || (major === 24 && minor < 9)) {
  throw new Error('DeepSeek Harness requires Node.js 24.9 or later.');
}

const directory = resolve(process.argv[2] ?? resolve(homedir(), '.xuexue', 'dsh-runtime'));
const nodeDirectory = dirname(realpathSync(process.execPath));
const environment = { ...process.env, PATH: [nodeDirectory, process.env.PATH].join(delimiter) };
const args = ['install', '--prefix', directory, '--no-audit', '--no-fund', '@deepseek-ai/dsh@0.2.2-rc.5'];
// The Windows npm launcher is a .cmd file; invoke npm's JavaScript entry directly.
const npm = [
  process.env.npm_execpath,
  process.env.XUEXUE_NPM_CLI,
  resolve(nodeDirectory, 'node_modules/npm/bin/npm-cli.js'),
  resolve(nodeDirectory, '../lib/node_modules/npm/bin/npm-cli.js'),
  resolve(nodeDirectory, 'npm'),
].find(path => path && existsSync(path));
if (!npm) throw new Error('Install npm alongside Node.js or set XUEXUE_NPM_CLI to npm-cli.js.');
const installation = spawnSync(process.execPath, [npm, ...args], { env: environment, stdio: 'inherit' });
if (installation.error) throw installation.error;
if (installation.status !== 0) process.exit(installation.status ?? 1);

const node = resolve(directory, process.platform === 'win32' ? 'node.exe' : 'node');
if (node !== process.execPath) copyFileSync(process.execPath, node);
if (process.platform !== 'win32') chmodSync(node, statSync(process.execPath).mode);
