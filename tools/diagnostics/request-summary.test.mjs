import test from 'node:test';
import assert from 'node:assert/strict';
import { summarizeUrl, summarizeFailure } from './request-summary.mjs';

test('retains useful endpoint without query or credentials', () => {
  assert.deepEqual(summarizeUrl('https://user:password@passport.twitch.tv/register?token=secret&email=private'),
    { origin: 'https://passport.twitch.tv', endpoint: 'register' });
});
test('redacts arbitrary paths and excludes unrelated services', () => {
  assert.equal(summarizeUrl('https://example.com/register'), null);
  assert.equal(summarizeUrl('https://twitch.tv.attacker.test/register'), null);
  assert.equal(summarizeUrl('not-a-url'), null);
  assert.deepEqual(summarizeUrl('https://www.twitch.tv/private-account/private-secret'), { origin: 'https://www.twitch.tv', endpoint: 'other' });
});
test('only records structured network failure codes', () => {
  assert.equal(summarizeFailure({errorText:'private token=secret'}).error, 'network-error');
  assert.equal(summarizeFailure({errorText:'net::ERR_NAME_NOT_RESOLVED'}).error, 'net::ERR_NAME_NOT_RESOLVED');
});
