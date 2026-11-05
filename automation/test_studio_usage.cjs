const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = fs.readFileSync(process.argv[2], 'utf8').split('/* ------------------------------------------------------------------ boot */')[0];
const ctx = vm.createContext({localStorage: {getItem: () => null}, window: {innerWidth:1440}});
vm.runInContext(source, ctx);
for (const usage of [null, undefined, {fresh:false,age_seconds:600,five_hour:2,seven_day:3}, {fresh:true,age_seconds:1,five_hour:80,seven_day:3}, {fresh:true,age_seconds:1,five_hour:2,seven_day:3}]) {
  ctx.d = {stage:'implement',usage,questions:[],receipts:[],results:[],events:[],features:[],approved:true};
  const output = vm.runInContext("attention(d, 'ready')",ctx);
  assert.equal(output.length, usage && usage.fresh && usage.five_hour<80 ? 0 : 1);
  if (!usage) {
    assert.match(output[0][2], /값 없음/);
    assert.equal(vm.runInContext("allowed(d).has('run')",ctx),false);
  }
}
console.log('PASS: missing, undefined, stale, limit, fresh usage; missing usage blocks run');
