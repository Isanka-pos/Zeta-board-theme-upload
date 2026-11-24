'use strict';
const { MongoClient } = require('mongodb');

// One client per serverless instance, reused between requests.
let cached = global.__zetaStats;
if (!cached) cached = global.__zetaStats = { client: null, promise: null, indexes: false };

async function getDb() {
  const uri = process.env.MONGODB_URI;
  if (!uri) throw new Error('MONGODB_URI is not set');
  if (!cached.promise) {
    cached.client = new MongoClient(uri, { maxPoolSize: 5, serverSelectionTimeoutMS: 8000, socketTimeoutMS: 20000 });
    cached.promise = cached.client.connect().catch((e) => {
      cached.promise = null; // allow a retry on the next request
      throw e;
    });
  }
  await cached.promise;
  const db = cached.client.db(process.env.MONGODB_DB || 'zeta_stats');
  if (!cached.indexes) {
    cached.indexes = true;
    try {
      await Promise.all([
        db.collection('events').createIndex({ t: 1 }, { expireAfterSeconds: 60 * 60 * 24 * 400 }),
        db.collection('events').createIndex({ e: 1, d: 1 }),
        db.collection('daily').createIndex({ d: 1 }),
        db.collection('daily').createIndex({ t: 1 }, { expireAfterSeconds: 60 * 60 * 24 * 400 }),
        db.collection('devices').createIndex({ fd: 1 }),
        db.collection('devices').createIndex({ ls: 1 }),
      ]);
    } catch (e) {
      cached.indexes = false; // try again next time, never block a request on this
    }
  }
  return db;
}

module.exports = { getDb };
