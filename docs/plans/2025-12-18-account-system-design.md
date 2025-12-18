# Account System & Subscription Design

## Overview

Multi-user account system with subscription-based premium features for the Vitruvian Trainer app. Users authenticate to separate their data locally, with cloud sync available for premium subscribers. Includes a routine marketplace for community sharing and a web portal for analytics and account management.

## Decisions Summary

| Decision | Choice |
|----------|--------|
| Authentication | Email/password + Google + Apple Sign-In |
| Backend | Firebase (Auth + Firestore) |
| Local data on login | Prompt user to import or keep separate |
| Subscription model | Free + single Premium tier |
| Pricing | $9.99/month or $59.99/year |
| Free tier philosophy | Generous - gate power features, not core functionality |
| Routine sharing | Community with profiles, anonymous sharing allowed |
| Moderation | Reactive only (report button) |
| Offline behavior | Offline-first, sync when possible |
| Desktop | Deprecated - replaced by web portal |

## Authentication & Account Architecture

### Authentication Flow

All users must create an account to use the app. Options:
- Email/password (Firebase Auth)
- Google Sign-In
- Apple Sign-In (required for iOS App Store)

No guest mode - the login/signup screen is the entry point.

### Data Storage Model

```
Free Users:
- Account exists in Firebase Auth (authentication only)
- All workout data stored locally in SQLite
- Data partitioned by user ID locally
- No cloud backup - data lives on device only

Premium Users:
- Same local SQLite storage (offline-first)
- Data syncs to Firestore when online
- Accessible across devices via cloud sync
```

### Multi-User Local Storage

Each device can have multiple accounts. Local SQLite tables include a `userId` column:
- When User A logs in: queries filter to `userId = A`
- When User B logs in: queries filter to `userId = B`
- Data is isolated even though it's the same local database

### First Login on Device with Existing Data

If local data exists from a different account:
1. Prompt: "Found X workouts from another account. Import to yours?"
2. Yes: re-assigns `userId` on those records to current user
3. No: data stays with original account (accessible if they log back in)

## Subscription System

### Tiers

**Free Tier:**
- Full workout tracking (all modes, all exercises)
- Local data storage (unlimited on device)
- 90 days workout history
- Basic stats (recent PRs, workout count)
- Unlimited custom routines
- Training cycles (create/edit your own)

**Premium Tier ($9.99/month or $59.99/year):**
- Everything in Free
- Unlimited workout history
- Cloud sync across devices
- Advanced analytics (trends, volume charts, muscle balance)
- Data export (CSV)
- Prebuilt cycle templates (PPL, Upper/Lower, etc.)
- Routine marketplace (share/download community routines)

### Content vs Functionality Split

| Free | Premium |
|------|---------|
| Create your own routines | + Download community routines |
| Create your own cycles | + Access prebuilt templates |
| Local data | + Cloud sync |
| 90 days history | + Unlimited history |
| Basic stats | + Advanced analytics |

### Subscription Infrastructure

- Google Play Billing (Android)
- StoreKit 2 (iOS)
- Stripe (Web portal)
- Firebase stores subscription status in `/users/{uid}/premiumStatus`
- Apps validate receipts server-side via Firebase Functions
- Status object: `{ active: boolean, expiresAt: timestamp, source: "play"|"appstore"|"stripe" }`

### Feature Gating

Non-premium users see locked features with an upgrade prompt - features are visible but gated, not hidden. This reminds users what they're missing.

### Grace Period

If subscription lapses, users keep read-only access to cloud data for 30 days. After that, cloud data is preserved but inaccessible until they resubscribe. Local data always remains accessible.

## Routine Marketplace

### Data Model

```
Firestore: /sharedRoutines/{routineId}
├── title
├── description
├── authorId (links to user, or null if anonymous)
├── authorAlias (display name or "Anonymous")
├── exercises[] (full routine data)
├── tags[] (e.g., "strength", "beginner", "upper body")
├── downloadCount
├── rating (average)
├── ratingCount
├── createdAt
├── updatedAt
└── reported: boolean
```

### Sharing Flow

1. User creates routine locally
2. Taps "Share to Community" (premium only)
3. Chooses: publish under username OR publish anonymously
4. Adds description and tags
5. Routine uploads to Firestore, becomes browsable

### Browsing & Downloading

- Browse by: Popular, Recent, Tags, Search
- Preview shows: name, author, description, exercise list, ratings
- "Download" copies routine to user's local storage
- Downloaded routines are independent - edits don't affect original

### Ratings & Reviews

- 1-5 star rating after downloading
- Optional short text review (≤280 chars)
- Author sees aggregate rating, can't see who rated
- Reviews visible on routine detail page

### Moderation

- Report button on each routine
- Reported routines flagged in Firestore (`reported: true`)
- Manual review, can hide or delete

## Data Architecture

### Local Database Schema Changes

Existing tables gain a `userId` column:

```sql
-- Existing tables modified
WorkoutSession   + userId TEXT NOT NULL
MetricSample     + userId TEXT NOT NULL (via session foreign key)
PersonalRecord   + userId TEXT NOT NULL
Routine          + userId TEXT NOT NULL
RoutineExercise  (no change - linked via routine)

-- New tables
User (
  id TEXT PRIMARY KEY,
  email TEXT,
  displayName TEXT,
  premiumStatus TEXT,  -- JSON blob
  lastSyncAt INTEGER,
  createdAt INTEGER
)

SyncQueue (
  id INTEGER PRIMARY KEY,
  userId TEXT,
  tableName TEXT,
  recordId TEXT,
  operation TEXT,  -- "insert", "update", "delete"
  payload TEXT,    -- JSON of the change
  createdAt INTEGER
)
```

### Sync Strategy (Premium Users)

1. All writes go to local SQLite first (offline-first)
2. Changes queue in `SyncQueue` table
3. Background job processes queue when online
4. Uploads to Firestore: `/users/{uid}/workouts/{id}`, etc.
5. On success, removes from queue
6. On login to new device, pulls Firestore → local SQLite

### Conflict Resolution

Last-write-wins with timestamp comparison. Conflicts are rare since:
- Workouts are append-only (no concurrent edits)
- Routines edited offline sync when online - latest timestamp wins
- If conflict detected, keep both versions, let user merge manually

### History Limit Enforcement (Free Users)

Local query filters to last 90 days. Older data isn't deleted - just hidden. Upgrading to premium instantly reveals full history.

## UI/UX Flow

### App Entry

```
Launch App
    ↓
Logged in? ──No──→ Auth Screen
    ↓ Yes              ↓
Home Screen      [Email/Password]
                 [Sign in with Google]
                 [Sign in with Apple]
                 [Create Account]
```

### Account Switcher

Settings screen includes account section:
- Current user displayed (email/name + avatar)
- "Switch Account" → shows accounts used on this device
- "Add Account" → goes to auth screen
- "Log Out" → returns to auth screen, data stays local

### Profile & Settings Screen

```
Settings
├── Account
│   ├── Email / Display Name
│   ├── Change Password (if email auth)
│   ├── Linked Accounts (Google, Apple)
│   └── Switch / Add Account
├── Subscription
│   ├── Current Plan (Free / Premium)
│   ├── Upgrade to Premium (if free)
│   ├── Manage Subscription (links to Play/App Store)
│   └── Restore Purchases
├── Data
│   ├── Cloud Sync Status (premium)
│   ├── Export Data (premium)
│   └── Delete Account
└── App Settings (existing: units, theme, etc.)
```

### Premium Upgrade Prompts

Gated features show a consistent upgrade UI:
- Feature preview (what it looks like)
- "Unlock with Premium" button
- Brief value prop (1 line)
- Price: "$9.99/mo or $59.99/yr"

Non-intrusive - users discover premium organically by hitting gates.

## Platform Architecture

### Mobile Apps (KMP)

```
shared/
├── commonMain/
│   ├── auth/
│   │   ├── AuthRepository.kt (interface)
│   │   └── UserSession.kt (data class)
│   ├── subscription/
│   │   ├── SubscriptionRepository.kt (interface)
│   │   └── PremiumStatus.kt (data class)
│   └── sync/
│       ├── SyncManager.kt (interface)
│       └── SyncQueue.kt
├── androidMain/
│   ├── auth/FirebaseAuthRepository.kt
│   ├── subscription/PlayBillingRepository.kt
│   └── sync/FirestoreSyncManager.kt
└── iosMain/
    ├── auth/FirebaseAuthRepository.kt
    ├── subscription/StoreKitRepository.kt
    └── sync/FirestoreSyncManager.kt
```

### Web Portal

```
Web Portal (Firebase Hosting)
├── Authentication
│   ├── Login (email, Google, Apple)
│   └── Password reset
├── Dashboard
│   ├── Recent workouts summary
│   ├── Quick stats (streak, PRs, volume)
│   └── Sync status
├── Analytics (Premium)
│   ├── Workout history (full, searchable)
│   ├── Progress charts (strength, volume over time)
│   ├── Muscle group balance
│   └── Exercise-specific trends
├── Routine Marketplace
│   ├── Browse / Search / Filter
│   ├── Routine detail + reviews
│   ├── Download to account (syncs to mobile)
│   ├── Share your routines
│   └── Manage your shared routines
├── Account Settings
│   ├── Profile (display name, creator alias)
│   ├── Linked auth providers
│   ├── Change password
│   └── Delete account
├── Subscription
│   ├── Current plan status
│   ├── Upgrade (Stripe checkout)
│   ├── Manage billing
│   └── View invoices
└── Data Export (Premium)
    └── Download CSV of all workout data
```

### Tech Stack (Web)

- Firebase Hosting (static site)
- Firebase Auth (same as mobile)
- Firestore (same data, same security rules)
- Stripe for web-based subscriptions

### Subscription Unification

User subscribes via Play, App Store, OR Stripe. All update the same `/users/{uid}/premiumStatus` in Firestore. Apps check this single source of truth.

## Security

- Firebase Auth handles credential security
- Firestore Security Rules enforce user data isolation
- Receipt validation server-side (Firebase Functions)
- No sensitive data in local storage unencrypted
- Account deletion removes Firestore data, local data optional
