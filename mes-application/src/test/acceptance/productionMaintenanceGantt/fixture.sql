-- Acceptance fixture for the production-maintenance Gantt board (cmmsMachineParts/productionMaintenanceGantt).
--
-- Loaded by run-acceptance.sh into a fresh database after mes_db_en.sql:
--   psql -X -v ON_ERROR_STOP=1 -v base_day=YYYY-MM-DD -d <uri> -f fixture.sql
--
-- DD means base_day and D1 means base_day + 1 day. Every timestamp is a wall-clock value computed as
-- base_day + interval.
--
-- Prerequisite: the caller must pass a base_day on which the application's time zone keeps one UTC offset from
-- DD to DD + 7 days. The base_day checks below accept any calendar date in YYYY-MM-DD form; they do not check
-- time zones.
--
-- Creates, in one transaction:
--   * factory PMG-F, division PMG-D, production lines PMG-A and PMG-B with their shifts;
--   * products, one operation, technologies PMG-T-BOTH, PMG-T-A-ONLY and PMG-T-ZERO with one root operation
--     component each, their technology production lines and six line changeover norms;
--   * planned events PMG-EV-SHUTDOWN and PMG-EV-DIVISION;
--   * one draft schedule PMG-<case> per acceptance case except browser:ganttButtonUnsavedChangesGuard, which reads
--     PMG-rowMapping; each with 11 orders (A1-A5, B1-B5, SPARE), one operation run per order and 10 positions
--     (every role except SPARE).
-- Then lists the created positions, norms, events and lines.
--
-- Writes nothing to public.basic_parameter. The layout reads the seed values
-- canchangeprodlineforacceptedorders = t and workstationsquantityfromproductionline = f.

\set ON_ERROR_STOP on

-- Fails when base_day is not passed, is not in YYYY-MM-DD form or is not a calendar date.
\if :{?base_day}
\else
DO $$
BEGIN
    RAISE EXCEPTION 'fixture.sql requires -v base_day=YYYY-MM-DD';
END
$$;
\endif

SELECT (:'base_day' ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$') AS pmg_base_day_is_iso \gset
\if :pmg_base_day_is_iso
\else
DO $$
BEGIN
    RAISE EXCEPTION 'fixture.sql requires base_day in YYYY-MM-DD form';
END
$$;
\endif

SELECT (:'base_day'::date IS NOT NULL) AS pmg_base_day_is_date \gset

BEGIN;

-- Fails when a PMG- master row or schedule already exists.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM public.basic_factory WHERE number LIKE 'PMG-%')
            OR EXISTS (SELECT 1 FROM public.basic_division WHERE number LIKE 'PMG-%')
            OR EXISTS (SELECT 1 FROM public.productionlines_productionline WHERE number LIKE 'PMG-%')
            OR EXISTS (SELECT 1 FROM public.basic_shift WHERE name LIKE 'PMG-%')
            OR EXISTS (SELECT 1 FROM public.basic_product WHERE number LIKE 'PMG-%')
            OR EXISTS (SELECT 1 FROM public.technologies_operation WHERE number LIKE 'PMG-%')
            OR EXISTS (SELECT 1 FROM public.technologies_technology WHERE number LIKE 'PMG-%')
            OR EXISTS (SELECT 1 FROM public.linechangeovernorms_linechangeovernorms WHERE number LIKE 'PMG-%')
            OR EXISTS (SELECT 1 FROM public.cmmsmachineparts_plannedevent WHERE number LIKE 'PMG-%')
            OR EXISTS (SELECT 1 FROM public.orders_productionlineschedule WHERE number LIKE 'PMG-%')
            OR EXISTS (SELECT 1 FROM public.orders_order WHERE number LIKE 'PMG-%') THEN
        RAISE EXCEPTION 'fixture.sql is already loaded: PMG- rows exist in this database';
    END IF;
END
$$;

-- ---------------------------------------------------------------------------------------------------------------
-- Master data
-- ---------------------------------------------------------------------------------------------------------------

-- Factory PMG-F.
INSERT INTO public.basic_factory (id, number, name, active)
VALUES (nextval('public.basic_factory_id_seq'), 'PMG-F', 'PMG factory', true);

-- Division PMG-D of factory PMG-F.
INSERT INTO public.basic_division (id, number, name, factory_id, active)
VALUES (nextval('public.basic_division_id_seq'), 'PMG-D', 'PMG division',
        (SELECT id FROM public.basic_factory WHERE number = 'PMG-F'), true);

-- Production lines PMG-A and PMG-B of division PMG-D, both production and active.
INSERT INTO public.productionlines_productionline (id, number, name, division_id, supportsalltechnologies,
                                                   quantityforotherworkstationtypes, active, production, isblocked)
SELECT nextval('public.productionlines_productionline_id_seq'), l.number, l.name,
       (SELECT id FROM public.basic_division WHERE number = 'PMG-D'), true, 1, true, true, false
FROM (VALUES (1, 'PMG-A', 'PMG line A'),
             (2, 'PMG-B', 'PMG line B')) AS l (ord, number, name)
ORDER BY l.ord;

-- Division PMG-D lists both lines.
INSERT INTO public.jointable_division_productionline (productionline_id, division_id)
SELECT pl.id, (SELECT id FROM public.basic_division WHERE number = 'PMG-D')
FROM public.productionlines_productionline pl
WHERE pl.number IN ('PMG-A', 'PMG-B')
ORDER BY pl.number;

-- Shift PMG-A works 6:00-22:00 and shift PMG-B works 6:00-14:00, every day of the week.
INSERT INTO public.basic_shift (id, name,
                                mondayworking, mondayhours, tuesdayworking, tuesdayhours,
                                wensdayworking, wensdayhours, thursdayworking, thursdayhours,
                                fridayworking, fridayhours, saturdayworking, saturdayhours,
                                sundayworking, sundayhours)
SELECT nextval('public.basic_shift_id_seq'), s.name,
       true, s.hours, true, s.hours,
       true, s.hours, true, s.hours,
       true, s.hours, true, s.hours,
       true, s.hours
FROM (VALUES (1, 'PMG-A', '6:00-22:00'),
             (2, 'PMG-B', '6:00-14:00')) AS s (ord, name, hours)
ORDER BY s.ord;

-- Line PMG-A uses shift PMG-A and line PMG-B uses shift PMG-B. Seed shift 1 stays unlinked.
INSERT INTO public.jointable_productionline_shift (productionline_id, shift_id)
SELECT pl.id, sh.id
FROM public.productionlines_productionline pl
JOIN public.basic_shift sh ON sh.name = pl.number
WHERE pl.number IN ('PMG-A', 'PMG-B')
ORDER BY pl.number;

-- Products PMG-P-BOTH, PMG-P-A-ONLY and PMG-P-ZERO, unit pc.
INSERT INTO public.basic_product (id, number, name, unit, entitytype)
SELECT nextval('public.basic_product_id_seq'), p.number, p.name, 'pc', '01particularProduct'
FROM (VALUES (1, 'PMG-P-BOTH', 'PMG product for both lines'),
             (2, 'PMG-P-A-ONLY', 'PMG product for line A only'),
             (3, 'PMG-P-ZERO', 'PMG product with zero duration')) AS p (ord, number, name)
ORDER BY p.ord;

-- Operation PMG-OP.
INSERT INTO public.technologies_operation (id, number, name)
VALUES (nextval('public.technologies_operation_id_seq'), 'PMG-OP', 'PMG operation');

-- Accepted master technologies, one per product.
INSERT INTO public.technologies_technology (id, number, name, product_id, technologygroup_id, master, state, active)
SELECT nextval('public.technologies_technology_id_seq'), t.number, t.name,
       (SELECT id FROM public.basic_product WHERE number = t.product_number), NULL, true, '02accepted', true
FROM (VALUES (1, 'PMG-T-BOTH', 'PMG technology for both lines', 'PMG-P-BOTH'),
             (2, 'PMG-T-A-ONLY', 'PMG technology for line A only', 'PMG-P-A-ONLY'),
             (3, 'PMG-T-ZERO', 'PMG technology with zero duration', 'PMG-P-ZERO'))
         AS t (ord, number, name, product_number)
ORDER BY t.ord;

-- One root operation component per technology: tj 3600 s for PMG-T-BOTH and PMG-T-A-ONLY, tj 0 for PMG-T-ZERO.
INSERT INTO public.technologies_technologyoperationcomponent (id, technology_id, operation_id, parent_id, entitytype,
                                                              priority, nodenumber, tpz, tj, timenextoperation,
                                                              productioninonecycle, istjdivisible,
                                                              quantityofworkstations, minstaff, optimalstaff,
                                                              tjdecreasesforenlargedstaff)
SELECT nextval('public.technologies_technologyoperationcomponent_id_seq'),
       (SELECT id FROM public.technologies_technology WHERE number = c.technology_number),
       (SELECT id FROM public.technologies_operation WHERE number = 'PMG-OP'),
       NULL, 'operation', 1, '1.', 0, c.tj, 0, 1, false, 1, 1, 1, false
FROM (VALUES (1, 'PMG-T-BOTH', 3600),
             (2, 'PMG-T-A-ONLY', 3600),
             (3, 'PMG-T-ZERO', 0)) AS c (ord, technology_number, tj)
ORDER BY c.ord;

-- Technology production lines: PMG-T-BOTH on PMG-A and PMG-B, PMG-T-A-ONLY on PMG-A only,
-- PMG-T-ZERO on PMG-A and PMG-B.
INSERT INTO public.technologies_technologyproductionline (id, productionline_id, technology_id)
SELECT nextval('public.technologies_technologyproductionline_id_seq'),
       (SELECT id FROM public.productionlines_productionline WHERE number = tl.line_number),
       (SELECT id FROM public.technologies_technology WHERE number = tl.technology_number)
FROM (VALUES (1, 'PMG-T-BOTH', 'PMG-A'),
             (2, 'PMG-T-BOTH', 'PMG-B'),
             (3, 'PMG-T-A-ONLY', 'PMG-A'),
             (4, 'PMG-T-ZERO', 'PMG-A'),
             (5, 'PMG-T-ZERO', 'PMG-B')) AS tl (ord, technology_number, line_number)
ORDER BY tl.ord;

-- Line changeover norms of changeover type 01forTechnology between specific technologies on a specific line,
-- no technology groups, 1800 s each.
INSERT INTO public.linechangeovernorms_linechangeovernorms (id, number, name, changeovertype, fromtechnology_id,
                                                            totechnology_id, fromtechnologygroup_id,
                                                            totechnologygroup_id, productionline_id, duration)
SELECT nextval('public.linechangeovernorms_linechangeovernorms_id_seq'), n.number, n.number,
       '01forTechnology',
       (SELECT id FROM public.technologies_technology WHERE number = n.from_technology_number),
       (SELECT id FROM public.technologies_technology WHERE number = n.to_technology_number),
       NULL, NULL,
       (SELECT id FROM public.productionlines_productionline WHERE number = n.line_number),
       1800
FROM (VALUES (1, 'PMG-N-A-BB', 'PMG-A', 'PMG-T-BOTH', 'PMG-T-BOTH'),
             (2, 'PMG-N-A-BA', 'PMG-A', 'PMG-T-BOTH', 'PMG-T-A-ONLY'),
             (3, 'PMG-N-A-AB', 'PMG-A', 'PMG-T-A-ONLY', 'PMG-T-BOTH'),
             (4, 'PMG-N-B-BB', 'PMG-B', 'PMG-T-BOTH', 'PMG-T-BOTH'),
             (5, 'PMG-N-B-ZB', 'PMG-B', 'PMG-T-ZERO', 'PMG-T-BOTH'),
             (6, 'PMG-N-B-BZ', 'PMG-B', 'PMG-T-BOTH', 'PMG-T-ZERO'))
         AS n (ord, number, line_number, from_technology_number, to_technology_number)
ORDER BY n.ord;

-- ---------------------------------------------------------------------------------------------------------------
-- Planned events
-- ---------------------------------------------------------------------------------------------------------------

-- PMG-EV-SHUTDOWN: requires shutdown, line PMG-B, DD + 2 days 07:00-09:00.
-- PMG-EV-DIVISION: no shutdown, no line and no workstation, division PMG-D (lines PMG-A and PMG-B),
--                  DD + 3 days 08:00-10:00.
-- Neither window overlaps a position.
INSERT INTO public.cmmsmachineparts_plannedevent (id, number, type, state, factory_id, division_id, productionline_id,
                                                  workstation_id, requiresshutdown, startdate, finishdate,
                                                  entityversion)
SELECT nextval('public.cmmsmachineparts_plannedevent_id_seq'), e.number, '01review', '01new',
       (SELECT id FROM public.basic_factory WHERE number = 'PMG-F'),
       (SELECT id FROM public.basic_division WHERE number = 'PMG-D'),
       (SELECT id FROM public.productionlines_productionline WHERE number = e.line_number),
       NULL, e.requiresshutdown,
       :'base_day'::date + e.start_offset,
       :'base_day'::date + e.finish_offset,
       0
FROM (VALUES (1, 'PMG-EV-SHUTDOWN', 'PMG-B', true, interval '2 days 07:00', interval '2 days 09:00'),
             (2, 'PMG-EV-DIVISION', NULL, false, interval '3 days 08:00', interval '3 days 10:00'))
         AS e (ord, number, line_number, requiresshutdown, start_offset, finish_offset)
ORDER BY e.ord;

-- ---------------------------------------------------------------------------------------------------------------
-- Per-case draft schedules
-- ---------------------------------------------------------------------------------------------------------------

-- base_day for the DO block below, dropped at COMMIT.
CREATE TEMP TABLE pmg_fixture_param ON COMMIT DROP AS SELECT :'base_day'::date AS base_day;

-- Schedule PMG-<case> of every case: starttime D1 06:00, state 01draft, duration basis 01timeConsumingTechnology,
-- production line change allowed. Order PMG-<case>-<role>: state 01pending, division PMG-D, planned quantity 1,
-- datefrom = startdate = the role's start and dateto = finishdate = the role's end in the layout below. For every
-- role except SPARE these equal the position's starttime and endtime; SPARE has no position.
-- Positions: no changeover norm, additionaltime 0.
--
-- Layout of every schedule (times on D1):
--   role   line   technology     start-end      used by
--   A1     PMG-A  PMG-T-BOTH     06:00-07:00    origin predecessor
--   A2     PMG-A  PMG-T-BOTH     07:30-08:30    dragged order in every case except rejectRouting
--   A3     PMG-A  PMG-T-BOTH     09:00-10:00
--   A4     PMG-A  PMG-T-BOTH     10:07-11:07    off-grid start
--   A5     PMG-A  PMG-T-A-ONLY   11:30-12:30    dragged in rejectRouting
--   B1     PMG-B  PMG-T-BOTH     06:00-07:00    destination predecessor
--   B2     PMG-B  PMG-T-BOTH     07:30-08:30    first downstream of the rollbackAfterPsSideEffect drop
--   B3     PMG-B  PMG-T-ZERO     09:00-10:00    second downstream of the rollbackAfterPsSideEffect drop;
--                                               not downstream of the D1 10:00 drop
--   B4     PMG-B  PMG-T-BOTH     11:00-12:00
--   B5     PMG-B  PMG-T-BOTH     12:30-13:30    last downstream on PMG-B
--   SPARE  PMG-A  PMG-T-BOTH     07:30-08:30    order dates only, no position; replacement order for the
--                                               rejectStaleBoard and concurrentMovedPositionWrite order_id writes
-- Every stored position lies inside its line's shift.
--
-- Expected results per case (times on D1 unless a day is named):
-- rowMapping: no move; rows Line, PMG-A and PMG-B; PMG-EV-SHUTDOWN on PMG-B; PMG-EV-DIVISION on PMG-A and PMG-B.
--
-- acceptedCrossRowMove, and concurrentMoves when move M1 wins: drop A2 -> PMG-B at D1 10:00.
-- after acceptedCrossRowMove: A2 PMG-B D1 10:00-11:00 norm PMG-N-B-ZB
-- after acceptedCrossRowMove: A3 PMG-A D1 07:30-08:30 norm PMG-N-A-BB (first downstream on the origin row)
-- after acceptedCrossRowMove: A4 PMG-A D1 09:00-10:00 norm PMG-N-A-BB
-- after acceptedCrossRowMove: A5 PMG-A D1 10:30-11:30 norm PMG-N-A-BA
-- after acceptedCrossRowMove: B4 PMG-B D1 11:30-12:30 norm PMG-N-B-BB (first downstream on the destination row)
-- after acceptedCrossRowMove: B5 PMG-B D1 13:00-14:00 norm PMG-N-B-BB
-- after acceptedCrossRowMove: A1, B1, B2 and B3 keep their stored values
--
-- concurrentMoves when move M2 wins: drop A2 -> PMG-B at D1 09:30.
-- after concurrentMoves M2: A2 PMG-B D1 09:30-10:30 norm PMG-N-B-ZB
-- after concurrentMoves M2: A3, A4 and A5 as after acceptedCrossRowMove
-- after concurrentMoves M2: B4 PMG-B D1 11:00-12:00 norm PMG-N-B-BB
-- after concurrentMoves M2: B5 PMG-B D1 12:30-13:30 norm PMG-N-B-BB
-- after concurrentMoves M2: A1, B1, B2 and B3 keep their stored values
-- concurrentMoves: exactly one of M1 and M2 is accepted; the other is rejected with the optimistic-lock message.
--
-- acceptedLaterSameRowMove: drop A2 -> PMG-A at D1 09:30.
-- after acceptedLaterSameRowMove: A2 PMG-A D1 09:30-10:30 norm PMG-N-A-BB
-- after acceptedLaterSameRowMove: A3 PMG-A D1 07:30-08:30 norm PMG-N-A-BB (before the anchor)
-- after acceptedLaterSameRowMove: A4 PMG-A D1 11:00-12:00 norm PMG-N-A-BB (first after the anchor)
-- after acceptedLaterSameRowMove: A5 PMG-A D1 12:30-13:30 norm PMG-N-A-BA
-- after acceptedLaterSameRowMove: A1 and B1-B5 keep their stored values
--
-- rollbackAfterPsSideEffect: drop A2 -> PMG-B at D1 07:00. Recompute writes PS rows for A3, A4, A5 and B2, then
-- B3 (PMG-T-ZERO) yields no data. The move is rejected with recomputeFailed, and every table is unchanged.
--
-- Rejected drops, each with no persisted change:
-- rejectRouting: A5 -> PMG-B at D1 12:00 (routing).
-- rejectShutdown: A2 -> PMG-B at DD + 2 days 07:30 (shutdown window, event PMG-EV-SHUTDOWN).
-- rejectCalendar: A2 -> PMG-B at D1 16:00 (outside working hours).
-- rejectStaleBoard: A2 order_id set to SPARE after the board renders, then A2 -> PMG-B at D1 10:00 (optimistic lock).
-- concurrentMovedPositionWrite, concurrentNeighbourWrite, concurrentMembershipChange: A2 -> PMG-B at D1 10:00 during
-- a concurrent write (optimistic lock).
DO $$
DECLARE
    v_base_day date;
    v_division_id bigint;
    v_case text;
    v_schedule_id bigint;
    v_order_id bigint;
    v_role record;
BEGIN
    SELECT p.base_day INTO STRICT v_base_day FROM pg_temp.pmg_fixture_param p;
    SELECT d.id INTO STRICT v_division_id FROM public.basic_division d WHERE d.number = 'PMG-D';

    FOREACH v_case IN ARRAY ARRAY['rowMapping', 'acceptedCrossRowMove', 'acceptedLaterSameRowMove', 'rejectRouting',
                                  'rejectShutdown', 'rejectCalendar', 'rejectStaleBoard',
                                  'concurrentMovedPositionWrite', 'concurrentNeighbourWrite',
                                  'concurrentMembershipChange', 'concurrentMoves', 'rollbackAfterPsSideEffect']
    LOOP
        -- Schedule PMG-<case>.
        INSERT INTO public.orders_productionlineschedule (id, number, name, starttime, state,
                                                          durationofordercalculatedonbasis, allowproductionlinechange)
        VALUES (nextval('public.orders_productionlineschedule_id_seq'), 'PMG-' || v_case, 'PMG-' || v_case,
                v_base_day + interval '1 day 06:00', '01draft', '01timeConsumingTechnology', true)
        RETURNING id INTO v_schedule_id;

        FOR v_role IN
            SELECT l.role_name, pl.id AS line_id, t.id AS technology_id, t.product_id, toc.id AS toc_id,
                   v_base_day + l.start_offset AS start_time, v_base_day + l.end_offset AS end_time,
                   l.has_position
            FROM (VALUES (1, 'A1', 'PMG-A', 'PMG-T-BOTH', interval '1 day 06:00', interval '1 day 07:00', true),
                         (2, 'A2', 'PMG-A', 'PMG-T-BOTH', interval '1 day 07:30', interval '1 day 08:30', true),
                         (3, 'A3', 'PMG-A', 'PMG-T-BOTH', interval '1 day 09:00', interval '1 day 10:00', true),
                         (4, 'A4', 'PMG-A', 'PMG-T-BOTH', interval '1 day 10:07', interval '1 day 11:07', true),
                         (5, 'A5', 'PMG-A', 'PMG-T-A-ONLY', interval '1 day 11:30', interval '1 day 12:30', true),
                         (6, 'B1', 'PMG-B', 'PMG-T-BOTH', interval '1 day 06:00', interval '1 day 07:00', true),
                         (7, 'B2', 'PMG-B', 'PMG-T-BOTH', interval '1 day 07:30', interval '1 day 08:30', true),
                         (8, 'B3', 'PMG-B', 'PMG-T-ZERO', interval '1 day 09:00', interval '1 day 10:00', true),
                         (9, 'B4', 'PMG-B', 'PMG-T-BOTH', interval '1 day 11:00', interval '1 day 12:00', true),
                         (10, 'B5', 'PMG-B', 'PMG-T-BOTH', interval '1 day 12:30', interval '1 day 13:30', true),
                         (11, 'SPARE', 'PMG-A', 'PMG-T-BOTH', interval '1 day 07:30', interval '1 day 08:30', false))
                     AS l (ord, role_name, line_number, technology_number, start_offset, end_offset, has_position)
            JOIN public.productionlines_productionline pl ON pl.number = l.line_number
            JOIN public.technologies_technology t ON t.number = l.technology_number
            JOIN public.technologies_technologyoperationcomponent toc
                 ON toc.technology_id = t.id AND toc.parent_id IS NULL
            ORDER BY l.ord
        LOOP
            -- Order PMG-<case>-<role>.
            INSERT INTO public.orders_order (id, number, name, state, product_id, technology_id, productionline_id,
                                             division_id, plannedquantity, datefrom, dateto, startdate, finishdate)
            VALUES (nextval('public.orders_order_id_seq'), 'PMG-' || v_case || '-' || v_role.role_name,
                    'PMG-' || v_case || '-' || v_role.role_name, '01pending', v_role.product_id,
                    v_role.technology_id, v_role.line_id, v_division_id, 1, v_role.start_time, v_role.end_time,
                    v_role.start_time, v_role.end_time)
            RETURNING id INTO v_order_id;

            -- One run of the root operation component of the order's technology.
            INSERT INTO public.basicproductioncounting_productioncountingoperationrun (id, order_id,
                                                                                      technologyoperationcomponent_id,
                                                                                      runs)
            VALUES (nextval('public.basicproductioncounting_productioncountingoperationrun_id_seq'), v_order_id,
                    v_role.toc_id, 1);

            -- Position of the order on its line, for every role except SPARE.
            IF v_role.has_position THEN
                INSERT INTO public.orders_productionlinescheduleposition (id, productionlineschedule_id, order_id,
                                                                          productionline_id, linechangeovernorm_id,
                                                                          starttime, endtime, additionaltime)
                VALUES (nextval('public.orders_productionlinescheduleposition_id_seq'), v_schedule_id, v_order_id,
                        v_role.line_id, NULL, v_role.start_time, v_role.end_time, 0);
            END IF;
        END LOOP;
    END LOOP;
END
$$;

-- Fails unless the master data, the events and every schedule are complete: 2 lines, 5 technology production lines,
-- 1 root operation component per technology, 6 norms of changeover type 01forTechnology with from and to
-- technology and line, 2 events with factory and division, 12 schedules with 11 orders, 11 operation runs and
-- 10 positions each.
DO $$
DECLARE
    v_schedule record;
    v_orders bigint;
    v_runs bigint;
    v_positions bigint;
BEGIN
    IF (SELECT count(*) FROM public.productionlines_productionline
        WHERE number IN ('PMG-A', 'PMG-B') AND production AND active AND division_id IS NOT NULL) <> 2
            OR (SELECT count(*) FROM public.jointable_division_productionline jdp
                JOIN public.productionlines_productionline pl ON pl.id = jdp.productionline_id
                WHERE pl.number LIKE 'PMG-%') <> 2
            OR (SELECT count(*) FROM public.jointable_productionline_shift jps
                JOIN public.productionlines_productionline pl ON pl.id = jps.productionline_id
                WHERE pl.number LIKE 'PMG-%') <> 2 THEN
        RAISE EXCEPTION 'fixture.sql: production lines PMG-A and PMG-B are incomplete';
    END IF;

    IF (SELECT count(*) FROM public.technologies_technologyproductionline tpl
        JOIN public.technologies_technology t ON t.id = tpl.technology_id
        WHERE t.number LIKE 'PMG-T-%' AND tpl.productionline_id IS NOT NULL) <> 5
            OR (SELECT count(*) FROM public.technologies_technologyoperationcomponent toc
                JOIN public.technologies_technology t ON t.id = toc.technology_id
                WHERE t.number LIKE 'PMG-T-%' AND t.product_id IS NOT NULL AND toc.parent_id IS NULL
                  AND toc.operation_id IS NOT NULL) <> 3 THEN
        RAISE EXCEPTION 'fixture.sql: technologies PMG-T-* are incomplete';
    END IF;

    IF (SELECT count(*) FROM public.linechangeovernorms_linechangeovernorms
        WHERE number LIKE 'PMG-N-%' AND changeovertype = '01forTechnology'
          AND fromtechnology_id IS NOT NULL AND totechnology_id IS NOT NULL
          AND productionline_id IS NOT NULL) <> 6 THEN
        RAISE EXCEPTION 'fixture.sql: changeover norms PMG-N-* are incomplete';
    END IF;

    IF (SELECT count(*) FROM public.cmmsmachineparts_plannedevent
        WHERE number LIKE 'PMG-EV-%' AND factory_id IS NOT NULL AND division_id IS NOT NULL) <> 2 THEN
        RAISE EXCEPTION 'fixture.sql: planned events PMG-EV-* are incomplete';
    END IF;

    IF (SELECT count(*) FROM public.orders_productionlineschedule WHERE number LIKE 'PMG-%') <> 12 THEN
        RAISE EXCEPTION 'fixture.sql: expected 12 PMG- schedules';
    END IF;

    FOR v_schedule IN
        SELECT s.id, s.number FROM public.orders_productionlineschedule s WHERE s.number LIKE 'PMG-%' ORDER BY s.id
    LOOP
        SELECT count(*) INTO v_orders
        FROM public.orders_order o
        WHERE o.number LIKE v_schedule.number || '-%';

        SELECT count(*) INTO v_runs
        FROM public.basicproductioncounting_productioncountingoperationrun r
        JOIN public.orders_order o ON o.id = r.order_id
        WHERE o.number LIKE v_schedule.number || '-%' AND r.technologyoperationcomponent_id IS NOT NULL;

        SELECT count(*) INTO v_positions
        FROM public.orders_productionlinescheduleposition p
        WHERE p.productionlineschedule_id = v_schedule.id AND p.order_id IS NOT NULL
          AND p.productionline_id IS NOT NULL;

        IF v_orders <> 11 OR v_runs <> 11 OR v_positions <> 10 THEN
            RAISE EXCEPTION
                'fixture.sql: schedule % has % orders, % operation runs and % positions (expected 11, 11, 10)',
                v_schedule.number, v_orders, v_runs, v_positions;
        END IF;
    END LOOP;
END
$$;

COMMIT;

-- ---------------------------------------------------------------------------------------------------------------
-- Listing
-- ---------------------------------------------------------------------------------------------------------------

-- Positions of every PMG- schedule, ordered by schedule number, line number and start.
SELECT s.number AS schedule_number,
       s.id AS schedule_id,
       o.number AS order_number,
       substring(o.number FROM '[^-]+$') AS role,
       pl.number AS line_number,
       p.id AS position_id,
       to_char(p.starttime, 'YYYY-MM-DD HH24:MI:SS') AS start_time,
       to_char(p.endtime, 'YYYY-MM-DD HH24:MI:SS') AS end_time
FROM public.orders_productionlinescheduleposition p
JOIN public.orders_productionlineschedule s ON s.id = p.productionlineschedule_id
JOIN public.orders_order o ON o.id = p.order_id
JOIN public.productionlines_productionline pl ON pl.id = p.productionline_id
WHERE s.number LIKE 'PMG-%'
ORDER BY s.number, pl.number, p.starttime;

-- Norm, event and line numbers with their ids.
SELECT k.kind, k.number, k.id
FROM (SELECT 'norm' AS kind, n.number, n.id
      FROM public.linechangeovernorms_linechangeovernorms n
      WHERE n.number LIKE 'PMG-%'
      UNION ALL
      SELECT 'event' AS kind, e.number, e.id
      FROM public.cmmsmachineparts_plannedevent e
      WHERE e.number LIKE 'PMG-%'
      UNION ALL
      SELECT 'line' AS kind, pl.number, pl.id
      FROM public.productionlines_productionline pl
      WHERE pl.number LIKE 'PMG-%') AS k
ORDER BY k.kind, k.number;
