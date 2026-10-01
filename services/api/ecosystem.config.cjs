module.exports = {
  apps: [{
    name: 'nowfocus-api',
    script: 'dist/main.js',
    cwd: __dirname, // main.ts loads .env from cwd
    instances: 1, // WebSocket fan-out is in-process; do not cluster
    max_memory_restart: '400M',
  }],
};
