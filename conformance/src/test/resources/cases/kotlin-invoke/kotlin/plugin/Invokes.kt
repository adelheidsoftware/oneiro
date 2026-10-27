package plugin

/** Objects called as functions: Kotlin calls their invoke, and so does their Lua. */
object Invokes {

	/** Doubles a value, as a function the library calls. */
	class Doubler : (Int) -> Int {

		override fun invoke(value: Int): Int = value * 2
	}

	/** Adds its step to a value, with an operator of its own rather than a function type. */
	class Stepper(private val step: Int) {

		operator fun invoke(value: Int): Int = value + step
	}

	/** One less than a value. */
	object Lower {

		operator fun invoke(value: Int): Int = value - 1
	}

	/** A machine of functions, which calls them from its method. */
	class Machine(val doubler: Doubler, val stepper: Stepper, val callback: (Int) -> Int) {

		/** The value through each of the machine's functions, and through some of its own, added up. */
		fun run(value: Int): Int {
			val plusOne = object : (Int) -> Int {
				override fun invoke(x: Int): Int = x + 1
			}
			val twice = Doubler()
			val fromLocals = twice(value) + Doubler()(value)
			val fromMembers = doubler(value) + this.stepper(value) + callback(value)
			val fromOthers = plusOne(value) + Lower(value)
			return fromLocals + fromMembers + fromOthers
		}
	}

	/** The value through a machine's functions, called on it from outside. */
	fun through(machine: Machine, value: Int): Int =
		machine.doubler(value) + machine.stepper(value) + machine.callback(value)
}
