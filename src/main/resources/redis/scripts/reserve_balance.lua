-- KEYS: [1] balanceKey (hash), [2] reservationKey
-- ARGV: [1] amount_cents, [2] txId, [3] reservationTtlSeconds
local balanceKey = KEYS[1]
local reservationKey = KEYS[2]
local amount = tonumber(ARGV[1])
local txId = ARGV[2]
local ttl = tonumber(ARGV[3])

local currentAvailable = tonumber(redis.call('HGET', balanceKey, 'available') or '-1')
if currentAvailable < 0 then
  return {err = 'NO_ACCOUNT'}
end
if currentAvailable < amount then
  return {err = 'INSUFFICIENT_FUNDS'}
end

-- decrement available (integer cents) and write reservation
redis.call('HINCRBY', balanceKey, 'available', -amount)
redis.call('HMSET', reservationKey, 'txId', txId, 'amount_cents', tostring(amount), 'balanceKey', balanceKey)
redis.call('EXPIRE', reservationKey, ttl)

return {ok = 'OK'}
