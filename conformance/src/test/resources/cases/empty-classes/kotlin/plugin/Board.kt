package plugin

/** Makes marks. */
object Board {

	fun mark(): Marker = Marker()

	fun marker(): () -> Marker = ::Marker
}
