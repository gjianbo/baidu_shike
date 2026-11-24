import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import vm from 'node:vm';

const source = readFileSync(
    new URL('../../app/src/main/java/com/hnwd/bdplayer/QuarkDriveActivity.kt', import.meta.url),
    'utf8',
);
const loginLogic = readFileSync(
    new URL('../../app/src/main/java/com/hnwd/bdplayer/QuarkLoginLogic.kt', import.meta.url),
    'utf8',
);
const origin = 'https://uop.quark.cn';
const bridgeName = '__bd_android';
assert.match(loginLogic, /const val QUARK_H5_LOGIN_HOST = "uop\.quark\.cn"/);
assert.match(source, /const val QUARK_H5_LOGIN_ORIGIN = "https:\/\/\$QUARK_H5_LOGIN_HOST"/);
assert.match(source, /const val BRIDGE_NAME = "__bd_android"/);
const kotlinHook = source.match(/private val H5_ST_HOOK = """([\s\S]*?)"""\.trimIndent\(\)/);
assert.ok(kotlinHook, 'Could not read H5_ST_HOOK from the Android source');
const hook = new vm.Script(kotlinHook[1]
    .replaceAll('$QUARK_H5_LOGIN_ORIGIN', origin)
    .replaceAll('$BRIDGE_NAME', bridgeName));

function openPage({ pageOrigin = origin, mainFrame = true } = {}) {
    const forwarded = [];
    const listeners = [];
    const context = vm.createContext({
        location: { origin: pageOrigin },
        [bridgeName]: { postMessage: data => forwarded.push(data) },
        addEventListener(name, listener) {
            assert.equal(name, 'message');
            listeners.push(listener);
        },
        mainFrame,
    });
    vm.runInContext('window = globalThis; window.top = mainFrame ? window : {}', context);
    hook.runInContext(context);
    return { context, forwarded, listeners };
}

test('registers the message listener once on the trusted top-level window', () => {
    const page = openPage();
    hook.runInContext(page.context);
    assert.equal(page.listeners.length, 1);
});

test('other origins and nested frames do not register a listener', () => {
    assert.equal(openPage({ pageOrigin: 'https://other.invalid' }).listeners.length, 0);
    assert.equal(openPage({ mainFrame: false }).listeners.length, 0);
});

test('forwards only a valid ticket sent by the trusted top-level window', () => {
    const page = openPage();
    page.context.receive = page.listeners[0];
    vm.runInContext([
        "receive({origin: 'https://other.invalid', source: window, data: 'A'.repeat(32)});",
        "receive({origin: location.origin, source: {}, data: 'A'.repeat(32)});",
        "receive({origin: location.origin, source: window, data: 'A'.repeat(31)});",
        "receive({origin: location.origin, source: window, data: '-'.repeat(32)});",
        "receive({origin: location.origin, source: window, data: {ticket: 'A'.repeat(32)}});",
        "receive({origin: location.origin, source: window, data: 'A'.repeat(32)});",
    ].join('\n'), page.context);
    assert.deepEqual(page.forwarded, ['A'.repeat(32)]);
});
