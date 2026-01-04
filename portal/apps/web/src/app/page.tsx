import Link from 'next/link'

export default function Home() {
  return (
    <main className="flex min-h-screen flex-col items-center justify-center p-24">
      <h1 className="text-4xl font-bold text-phoenix-500">
        Phoenix Portal
      </h1>
      <p className="mt-4 text-gray-600 dark:text-gray-400">
        Premium analytics for your Vitruvian workouts
      </p>
      <div className="mt-8 flex gap-4">
        <Link
          href="/login"
          className="px-6 py-3 bg-phoenix-500 text-white rounded-lg hover:bg-phoenix-600 transition-colors"
        >
          Sign In
        </Link>
        <Link
          href="/signup"
          className="px-6 py-3 border border-phoenix-500 text-phoenix-500 rounded-lg hover:bg-phoenix-500 hover:text-white transition-colors"
        >
          Create Account
        </Link>
      </div>
    </main>
  )
}
