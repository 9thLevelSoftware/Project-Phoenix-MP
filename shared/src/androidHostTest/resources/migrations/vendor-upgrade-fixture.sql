CREATE TABLE WorkoutSession (
    id TEXT NOT NULL PRIMARY KEY,
    timestamp INTEGER NOT NULL,
    mode TEXT NOT NULL,
    targetReps INTEGER NOT NULL,
    weightPerCableKg REAL NOT NULL
);
CREATE TABLE Routine (
    id TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    createdAt INTEGER NOT NULL
);
CREATE TABLE ConnectionLog (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp INTEGER NOT NULL,
    eventType TEXT NOT NULL,
    level TEXT NOT NULL,
    message TEXT NOT NULL
);
INSERT INTO WorkoutSession (id, timestamp, mode, targetReps, weightPerCableKg) VALUES ('fixture-session', 2, 'Pump', 12, 30.0);
INSERT INTO Routine (id, name, createdAt) VALUES ('fixture-routine', 'Fixture Routine', 2);
INSERT INTO ConnectionLog (timestamp, eventType, level, message) VALUES (2, 'SCAN', 'DEBUG', 'fixture');
