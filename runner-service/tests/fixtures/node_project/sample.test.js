const test = require("node:test");
const assert = require("node:assert/strict");

test("runner executes Node tests", () => {
  assert.equal(2 + 2, 4);
});
