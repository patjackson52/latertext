package com.patjackson.latertext.core.domain.recurrence

import com.patjackson.latertext.core.model.MonthlyAdjustment
import com.patjackson.latertext.core.model.MonthlyDayPolicy
import com.patjackson.latertext.core.model.RecurrenceEnd
import com.patjackson.latertext.core.model.RecurrenceFrequency
import com.patjackson.latertext.core.model.RecurrenceRule
import com.patjackson.latertext.core.model.RecurrenceSlot
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/** Generates recurrence slots in civil-time order without consulting wall-clock time. */
class RecurrenceGenerator {
    fun slots(rule: RecurrenceRule): Sequence<RecurrenceSlot> = when (rule.frequency) {
        RecurrenceFrequency.ONCE -> once(rule)
        RecurrenceFrequency.DAILY -> daily(rule)
        RecurrenceFrequency.WEEKLY -> weekly(rule)
        RecurrenceFrequency.MONTHLY -> monthly(rule)
    }

    private fun once(rule: RecurrenceRule): Sequence<RecurrenceSlot> = sequence {
        if (allowedByEnd(rule, rule.startDate, 0)) {
            yield(RecurrenceSlot(0, rule.startDate.atTime(rule.localTime)))
        }
    }

    private fun daily(rule: RecurrenceRule): Sequence<RecurrenceSlot> = sequence {
        var sequence = 0L
        var cycle = 0L
        while (true) {
            val date = rule.startDate.plusDays(Math.multiplyExact(cycle, rule.interval.toLong()))
            if (!allowedByEnd(rule, date, sequence)) break
            yield(RecurrenceSlot(sequence, date.atTime(rule.localTime)))
            sequence++
            cycle++
        }
    }

    private fun weekly(rule: RecurrenceRule): Sequence<RecurrenceSlot> = sequence {
        val anchorWeek = rule.startDate.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        val orderedDays = rule.daysOfWeek.sortedBy { it.value }
        var sequence = 0L
        var cycle = 0L
        while (true) {
            val weekStart = anchorWeek.plusWeeks(Math.multiplyExact(cycle, rule.interval.toLong()))
            var producedInCycle = false
            for (day in orderedDays) {
                val date = weekStart.plusDays((day.value - 1).toLong())
                if (date.isBefore(rule.startDate)) continue
                if (!allowedByEnd(rule, date, sequence)) return@sequence
                yield(RecurrenceSlot(sequence, date.atTime(rule.localTime)))
                producedInCycle = true
                sequence++
            }
            cycle++
            // An end-count of zero is disallowed. This guard only protects pathological overflow.
            if (!producedInCycle && cycle == Long.MAX_VALUE) return@sequence
        }
    }

    private fun monthly(rule: RecurrenceRule): Sequence<RecurrenceSlot> = sequence {
        val anchorMonth = YearMonth.from(rule.startDate)
        var sequence = 0L
        var cycle = 0L
        while (true) {
            val month = anchorMonth.plusMonths(Math.multiplyExact(cycle, rule.interval.toLong()))
            val dateAndAdjustment = monthlyDate(month, rule.dayOfMonth, rule.monthlyDayPolicy)
            if (dateAndAdjustment != null) {
                val (date, adjustment) = dateAndAdjustment
                if (!date.isBefore(rule.startDate)) {
                    if (!allowedByEnd(rule, date, sequence)) break
                    yield(
                        RecurrenceSlot(
                            sequence = sequence,
                            nominalLocalDateTime = date.atTime(rule.localTime),
                            monthlyAdjustment = adjustment,
                        ),
                    )
                    sequence++
                }
            } else if (endDateHasPassed(rule.end, month.atDay(1))) {
                break
            }
            cycle++
        }
    }

    private fun monthlyDate(
        month: YearMonth,
        requestedDay: Int,
        policy: MonthlyDayPolicy,
    ): Pair<LocalDate, MonthlyAdjustment>? {
        if (requestedDay <= month.lengthOfMonth()) {
            return month.atDay(requestedDay) to MonthlyAdjustment.NONE
        }
        return when (policy) {
            MonthlyDayPolicy.SKIP_MONTH -> null
            MonthlyDayPolicy.LAST_DAY_OF_MONTH ->
                month.atEndOfMonth() to MonthlyAdjustment.USED_LAST_DAY_OF_MONTH
        }
    }

    private fun allowedByEnd(rule: RecurrenceRule, date: LocalDate, sequence: Long): Boolean =
        when (val end = rule.end) {
            RecurrenceEnd.Never -> true
            is RecurrenceEnd.OnDate -> !date.isAfter(end.inclusiveDate)
            is RecurrenceEnd.AfterOccurrences -> sequence < end.count
        }

    private fun endDateHasPassed(end: RecurrenceEnd, candidateMonthStart: LocalDate): Boolean =
        end is RecurrenceEnd.OnDate && candidateMonthStart.isAfter(end.inclusiveDate)
}
