import { createClient } from '@/lib/supabase/server'
import { redirect } from 'next/navigation'

export default async function DashboardPage() {
  const supabase = await createClient()
  const { data: { user } } = await supabase.auth.getUser()

  // Defensive null check (layout should protect, but be safe)
  if (!user) {
    redirect('/login')
  }

  // Fetch profile with error handling
  const { data: profile, error } = await supabase
    .from('profiles')
    .select('display_name')
    .eq('id', user.id)
    .single()

  if (error && error.code !== 'PGRST116') {
    console.error('Failed to fetch profile:', error.message)
  }

  return (
    <div className="space-y-6">
      <div className="bg-white dark:bg-gray-800 shadow rounded-lg p-6">
        <h2 className="text-2xl font-bold text-gray-900 dark:text-white">
          Welcome, {profile?.display_name || 'Athlete'}!
        </h2>
        <p className="mt-2 text-gray-600 dark:text-gray-400">
          Your premium analytics dashboard is coming soon.
        </p>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
        <div className="bg-white dark:bg-gray-800 shadow rounded-lg p-6">
          <h3 className="text-lg font-medium text-gray-900 dark:text-white">Workouts</h3>
          <p className="mt-2 text-3xl font-bold text-phoenix-500">--</p>
          <p className="text-sm text-gray-500">Total sessions</p>
        </div>

        <div className="bg-white dark:bg-gray-800 shadow rounded-lg p-6">
          <h3 className="text-lg font-medium text-gray-900 dark:text-white">Volume</h3>
          <p className="mt-2 text-3xl font-bold text-phoenix-500">-- kg</p>
          <p className="text-sm text-gray-500">This week</p>
        </div>

        <div className="bg-white dark:bg-gray-800 shadow rounded-lg p-6">
          <h3 className="text-lg font-medium text-gray-900 dark:text-white">PRs</h3>
          <p className="mt-2 text-3xl font-bold text-phoenix-500">--</p>
          <p className="text-sm text-gray-500">Personal records</p>
        </div>
      </div>

      <div className="bg-white dark:bg-gray-800 shadow rounded-lg p-6">
        <h3 className="text-lg font-medium text-gray-900 dark:text-white mb-4">
          Sync Status
        </h3>
        <p className="text-gray-600 dark:text-gray-400">
          No data synced yet. Connect your Phoenix mobile app to see your analytics here.
        </p>
      </div>
    </div>
  )
}
