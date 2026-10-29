// Offline checks for the drag-to-reorder maths. Runs on a normal computer, no phone needed.
// See tools/drag-test/run.sh
import com.prism.musicplayer.ui.DragMath
import com.prism.musicplayer.ui.ItemSpan
import kotlin.math.abs

var failures = 0

fun check(name: String, ok: Boolean, detail: String = "") {
    println((if (ok) "PASS  " else "FAIL  ") + name + (if (detail.isNotEmpty()) "  [$detail]" else ""))
    if (!ok) failures++
}

/** A pretend list of rows that are all [rowHeight] tall, with one row being dragged by a finger. */
class Sim(count: Int, val rowHeight: Int = 100) {
    var order = (0 until count).toMutableList()
    var dragged = -1          // the row (by id) being dragged
    var dragOffset = 0f
    var fingerStartVisualTop = 0f
    var fingerTotal = 0f
    var maxJump = 0f          // biggest sudden change of the row's on-screen position that wasn't the finger

    fun spans() = order.indices.map { ItemSpan(it, it * rowHeight, rowHeight) }
    fun naturalTop() = order.indexOf(dragged) * rowHeight.toFloat()
    fun visualTop() = naturalTop() + dragOffset

    fun start(id: Int) { dragged = id; dragOffset = 0f; fingerStartVisualTop = naturalTop(); fingerTotal = 0f }

    /** The finger moves by [dy]. The list reacts like the real screen does (one trade at a time, then lays out again). */
    fun drag(dy: Float) {
        val before = visualTop()
        dragOffset += dy
        fingerTotal += dy
        while (true) {
            val r = DragMath.swap(spans(), order.indexOf(dragged), dragOffset) ?: break
            order = DragMath.moveItem(order, order.indexOf(dragged), r.toIndex).toMutableList()
            dragOffset = r.newDragOffset
        }
        maxJump = maxOf(maxJump, abs(visualTop() - before - dy))
    }

    fun dragTo(totalDy: Float, step: Float) {
        var left = totalDy
        while (abs(left) > 0.001f) {
            val d = if (abs(left) < step) left else if (left > 0) step else -step
            drag(d); left -= d
        }
    }
}

fun main() {
    println("Drag-to-reorder tests\n")

    run {
        val s = Sim(10); s.start(2); s.dragTo(250f, 10f)
        check("Dragging down 2.5 rows moves the row 2 or 3 places", s.order.indexOf(2) in 4..5, s.order.toString())
    }
    run {
        val s = Sim(10); s.start(2); s.dragTo(300f, 10f)
        check("Dragging down 3 rows lands 3 places lower", s.order.indexOf(2) == 5 && s.order == listOf(0, 1, 3, 4, 5, 2, 6, 7, 8, 9), s.order.toString())
    }
    run {
        val s = Sim(10); s.start(7); s.dragTo(-400f, 10f)
        check("Dragging up 4 rows lands 4 places higher", s.order.indexOf(7) == 3, s.order.toString())
    }
    run {
        val s = Sim(10); s.start(4); s.dragTo(30f, 5f)
        check("A small nudge doesn't change anything", s.order == (0 until 10).toList(), s.order.toString())
    }
    run {
        val s = Sim(10); s.start(4); s.dragTo(60f, 5f)
        check("Passing half a row swaps with the next one", s.order.indexOf(4) == 5)
    }
    run {
        // the row follows the finger exactly: crossing the line back and forth swaps back and forth, without any jump
        val s = Sim(10); s.start(4); s.dragTo(60f, 5f)
        val swappedDown = s.order.indexOf(4) == 5
        s.dragTo(-20f, 5f)
        check("Wobbling across the middle line swaps back and forth with the finger, smoothly",
            swappedDown && s.order.indexOf(4) == 4 && s.maxJump < 0.01f, s.order.toString())
    }
    run {
        val s = Sim(10); s.start(0); s.dragTo(-500f, 20f)
        check("Dragging above the top keeps the row first", s.order.first() == 0)
    }
    run {
        val s = Sim(10); s.start(9); s.dragTo(800f, 20f)
        check("Dragging below the bottom keeps the row last", s.order.last() == 9)
    }
    run {
        val s = Sim(10); s.start(3); s.dragTo(500f, 7f)
        check("The row stays under the finger (no jumps) while moving", s.maxJump < 0.01f, "biggest jump ${s.maxJump}")
        check("...and its screen position follows the finger", abs(s.visualTop() - (s.fingerStartVisualTop + s.fingerTotal)) < 0.01f)
    }
    run {
        val s = Sim(10); s.start(3); s.dragTo(500f, 400f)
        check("A very fast drag (several rows in one step) still lands right", s.order.indexOf(3) in 7..8 && s.maxJump < 0.01f, s.order.toString())
    }
    run {
        val s = Sim(10); s.start(3); s.dragTo(300f, 10f); s.dragTo(-300f, 10f)
        check("Dragging down and back up restores the order", s.order == (0 until 10).toList(), s.order.toString())
    }
    run {
        val s = Sim(50); s.start(10); s.dragTo(3000f, 12f)
        check("A long drag keeps every row exactly once", s.order.sorted() == (0 until 50).toList() && s.order.indexOf(10) == 40, "now at ${s.order.indexOf(10)}")
    }
    check("Nothing to swap with when the list has one row", DragMath.swap(listOf(ItemSpan(0, 0, 100)), 0, 500f) == null)
    check("Unknown dragged row does nothing", DragMath.swap(listOf(ItemSpan(0, 0, 100)), 5, 0f) == null)

    // auto-scroll
    check("No auto-scroll in the middle of the list", DragMath.autoScroll(300f, 400f, 1000f, 120f, 30f) == 0f)
    check("Auto-scroll up near the top", DragMath.autoScroll(20f, 120f, 1000f, 120f, 30f) < 0f)
    check("Auto-scroll down near the bottom", DragMath.autoScroll(880f, 980f, 1000f, 120f, 30f) > 0f)
    check("Faster closer to the edge", DragMath.autoScroll(0f, 100f, 1000f, 120f, 30f) < DragMath.autoScroll(80f, 180f, 1000f, 120f, 30f))
    check("Never faster than the maximum", DragMath.autoScroll(-500f, -400f, 1000f, 120f, 30f) >= -30f && DragMath.autoScroll(2000f, 2100f, 1000f, 120f, 30f) <= 30f)

    // moveItem
    check("moveItem down", DragMath.moveItem(listOf(1, 2, 3, 4), 0, 2) == listOf(2, 3, 1, 4))
    check("moveItem up", DragMath.moveItem(listOf(1, 2, 3, 4), 3, 1) == listOf(1, 4, 2, 3))
    check("moveItem out of range does nothing", DragMath.moveItem(listOf(1, 2), 0, 5) == listOf(1, 2))

    println()
    println(if (failures == 0) "ALL TESTS PASSED" else "$failures TEST(S) FAILED")
    if (failures != 0) System.exit(1)
}
