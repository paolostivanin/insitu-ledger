package api

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"math/rand/v2"
	"strings"
	"testing"
)

// TestBatchUpdate_RandomizedInvariants fires random batch-edit requests at
// random (but fixed-seed) worlds and compares every outcome with an
// independent model of the contract:
//
//   - non-200: the database is byte-identical (every table a batch edit
//     could touch, sync_meta included);
//   - 200: exactly the rows the model predicts changed, only in description /
//     category_id (to the predicted values) plus sync_version / updated_at,
//     sync_meta moved once per written row, and the response counts equal the
//     model's.
//
// The model (predictBatchEdit) re-derives the rule table from the API
// contract and shares no code with handlers_batch.go.
func TestBatchUpdate_RandomizedInvariants(t *testing.T) {
	const iterations, steps = 40, 12
	rng := rand.New(rand.NewPCG(20261125, 0x5eed))
	tally := map[string]int{}

	for iter := 0; iter < iterations; iter++ {
		// Fresh server per iteration: fresh DB and a fresh 300 req/min limiter.
		f := newBatchFixture(t)
		seedRandomWorld(f, rng)
		tokens := map[int64]string{f.adminID: f.admin, f.guestID: f.guest}

		for step := 0; step < steps; step++ {
			world := loadModelWorld(t, f.s.DB)
			req := randomBatchRequest(rng, world, f)
			want := predictBatchEdit(world, req)
			label := fmt.Sprintf("iter %d step %d: POST %s as user %d: %.300s", iter, step, req.path(), req.actor, req.body())

			before := takeSnapshot(t, f.s.DB)
			w := f.post(req.path(), tokens[req.actor], req.body())
			after := takeSnapshot(t, f.s.DB)

			body := strings.TrimSpace(w.Body.String())
			if w.Code != want.status {
				t.Fatalf("%s\ngot %d %q, model predicts %d %q", label, w.Code, body, want.status, want.msg)
			}
			if want.status != 200 {
				if body != want.msg {
					t.Fatalf("%s\ngot message %q, model predicts %q", label, body, want.msg)
				}
				assertNoChanges(t, label, before, after)
				tally[want.msg]++
				continue
			}

			var got map[string]int
			if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
				t.Fatalf("%s\ndecode %q: %v", label, body, err)
			}
			if fmt.Sprint(got) != fmt.Sprint(want.counts) {
				t.Fatalf("%s\ncounts %v, model predicts %v", label, got, want.counts)
			}
			verifyModelEdits(t, label, before, after, want)

			switch {
			case len(want.txnEdits)+len(want.schedEdits) == 0 && got["unchanged"] == 0:
				tally["200 nothing live"]++
			case len(want.txnEdits)+len(want.schedEdits) == 0:
				tally["200 all unchanged"]++
			default:
				tally["200 with writes"]++
			}
			if req.scheduled && len(want.schedEdits) > 0 {
				tally["200 schedule endpoint writes"]++
			}
			if !req.scheduled && len(want.schedEdits) > 0 {
				tally["200 linked schedule writes"]++
			}
			if got["schedules_skipped"] > 0 {
				tally["200 linked schedule skipped"]++
			}
		}
	}

	t.Logf("outcomes: %v", tally)
	// The seed is fixed, so this is deterministic. If a generator change
	// stops exercising a branch, fail loudly rather than silently test less.
	for _, k := range []string{
		"200 with writes", "200 all unchanged", "200 nothing live", "200 schedule endpoint writes",
		"200 linked schedule writes", "200 linked schedule skipped",
		msgInvalidBody, msgIDsRequired, msgNothingToUpdate, msgInvalidID, msgEmptyDescription,
		msgDescriptionLong, msgInvalidCategory, msgForbidden, msgMultiOwner, msgCategoryNotFound,
		msgMixedTypes, msgTypeMismatch, msgScheduleOwner, msgScheduleType,
	} {
		if tally[k] == 0 {
			t.Errorf("no request exercised %q", k)
		}
	}
}

// ===== World =====

// seedRandomWorld adds, on top of the fixture: a revoked share of the
// private account, a soft-deleted account (with live rows in it), a deleted
// guest category, 8 schedules in mixed states and 60 varied transactions,
// some linked to schedules of a different type, owner or account.
func seedRandomWorld(f *batchFix, rng *rand.Rand) {
	f.exec(`INSERT INTO shared_account_access (owner_user_id, guest_user_id, account_id, deleted_at) VALUES (?, ?, ?, datetime('now'))`,
		f.adminID, f.guestID, f.private)
	closed := f.account(f.adminID, "Closed")
	f.exec(`UPDATE accounts SET deleted_at = datetime('now') WHERE id = ?`, closed)
	guestOld := f.category(f.guestID, "Old guest", "expense", true)

	owner := map[int64]int64{f.shared: f.adminID, f.private: f.adminID, f.guestAcct: f.guestID, closed: f.adminID}
	other := map[int64]int64{f.adminID: f.guestID, f.guestID: f.adminID}
	cats := map[int64]map[string][]int64{
		f.adminID: {"expense": {f.food, f.transport, f.deletedCat}, "income": {f.salary, f.bonus}},
		f.guestID: {"expense": {f.guestFood, guestOld}, "income": {f.guestSalary}},
	}
	accounts := []int64{f.shared, f.shared, f.shared, f.private, f.private, f.guestAcct, f.guestAcct, closed}
	types := []string{"expense", "income"}
	descs := []any{
		"Stipendio", "stipendio", "Stipendio Lisa", "  Stipendio  ", "Rent", "Coffee", nil, "Netflix",
		"è", "it's", strings.Repeat("x", 500),
	}
	pickAny := func(xs []any) any { return xs[rng.IntN(len(xs))] }
	pickID := func(xs []int64) int64 { return xs[rng.IntN(len(xs))] }
	creator := func(acct int64) int64 {
		if rng.IntN(4) == 0 {
			return other[owner[acct]]
		}
		return owner[acct]
	}

	var scheds []int64
	schedAcct := map[int64]int64{}
	for i := 0; i < 8; i++ {
		acct := pickID(accounts)
		typ := types[rng.IntN(2)]
		s := schedSeed{
			account: acct, category: pickID(cats[owner[acct]][typ]), typ: typ, desc: pickAny(descs),
			createdBy: creator(acct), count: int64(rng.IntN(5)),
		}
		switch rng.IntN(10) {
		case 0, 1:
			s.paused = true
		case 2, 3:
			s.finished, s.maxOcc = true, s.count
		case 4:
			s.deleted = true
		}
		id := f.sched(s)
		scheds = append(scheds, id)
		schedAcct[id] = acct
	}

	for i := 0; i < 60; i++ {
		acct := pickID(accounts)
		typ := types[rng.IntN(2)]
		catType := typ
		if rng.IntN(8) == 0 { // occasionally a category of the other type
			catType = map[string]string{"expense": "income", "income": "expense"}[typ]
		}
		s := txnSeed{
			account: acct, category: pickID(cats[owner[acct]][catType]), typ: typ, desc: pickAny(descs),
			amount: float64(1 + rng.IntN(500)), deleted: rng.IntN(7) == 0,
		}
		switch rng.IntN(8) {
		case 0:
			s.nullCreator = true
		default:
			s.createdBy = creator(acct)
		}
		if rng.IntN(5) < 2 {
			// Mostly a schedule of the same account; sometimes any schedule.
			sid := pickID(scheds)
			if rng.IntN(3) > 0 {
				for _, cand := range scheds {
					if schedAcct[cand] == acct && rng.IntN(2) == 0 {
						sid = cand
						break
					}
				}
			}
			s.scheduledID, s.occurrence = sid, int64(1+rng.IntN(4))
		}
		f.txn(s)
	}
}

// ===== Model =====

type modelRow struct {
	id, account, owner, cat int64
	typ                     string
	desc                    *string
	sched                   int64 // linked schedule (transactions only); 0 = none
	deleted                 bool
}

type modelWorld struct {
	txns, scheds map[int64]modelRow
	accounts     map[int64]struct {
		owner   int64
		deleted bool
	}
	cats map[int64]struct {
		owner   int64
		typ     string
		deleted bool
	}
	shares map[[3]int64]bool // live {owner, guest, account}

	// Sorted, so random picks don't depend on map order.
	txnIDs, schedIDs, catIDs []int64
}

func loadModelWorld(t *testing.T, db *sql.DB) modelWorld {
	t.Helper()
	w := modelWorld{txns: map[int64]modelRow{}, scheds: map[int64]modelRow{}, shares: map[[3]int64]bool{}}
	w.accounts = map[int64]struct {
		owner   int64
		deleted bool
	}{}
	w.cats = map[int64]struct {
		owner   int64
		typ     string
		deleted bool
	}{}

	query := func(q string, scan func(*sql.Rows) error) {
		t.Helper()
		rs, err := db.Query(q)
		if err != nil {
			t.Fatalf("model load %q: %v", q, err)
		}
		defer rs.Close()
		for rs.Next() {
			if err := scan(rs); err != nil {
				t.Fatalf("model scan %q: %v", q, err)
			}
		}
		if err := rs.Err(); err != nil {
			t.Fatalf("model load %q: %v", q, err)
		}
	}
	scanRow := func(rs *sql.Rows, withSched bool) (modelRow, error) {
		var r modelRow
		var desc sql.NullString
		var sched sql.NullInt64
		var deleted sql.NullString
		dest := []any{&r.id, &r.account, &r.owner, &r.cat, &r.typ, &desc, &deleted}
		if withSched {
			dest = append(dest, &sched)
		}
		if err := rs.Scan(dest...); err != nil {
			return r, err
		}
		if desc.Valid {
			d := desc.String
			r.desc = &d
		}
		r.sched = sched.Int64
		r.deleted = deleted.Valid
		return r, nil
	}

	query(`SELECT id, account_id, user_id, category_id, type, description, CAST(deleted_at AS TEXT), scheduled_id FROM transactions ORDER BY id`,
		func(rs *sql.Rows) error {
			r, err := scanRow(rs, true)
			w.txns[r.id] = r
			w.txnIDs = append(w.txnIDs, r.id)
			return err
		})
	query(`SELECT id, account_id, user_id, category_id, type, description, CAST(deleted_at AS TEXT) FROM scheduled_transactions ORDER BY id`,
		func(rs *sql.Rows) error {
			r, err := scanRow(rs, false)
			w.scheds[r.id] = r
			w.schedIDs = append(w.schedIDs, r.id)
			return err
		})
	query(`SELECT id, user_id, deleted_at IS NOT NULL FROM accounts`, func(rs *sql.Rows) error {
		var id int64
		var a struct {
			owner   int64
			deleted bool
		}
		err := rs.Scan(&id, &a.owner, &a.deleted)
		w.accounts[id] = a
		return err
	})
	query(`SELECT id, user_id, type, deleted_at IS NOT NULL FROM categories ORDER BY id`, func(rs *sql.Rows) error {
		var id int64
		var c struct {
			owner   int64
			typ     string
			deleted bool
		}
		err := rs.Scan(&id, &c.owner, &c.typ, &c.deleted)
		w.cats[id] = c
		w.catIDs = append(w.catIDs, id)
		return err
	})
	query(`SELECT owner_user_id, guest_user_id, account_id FROM shared_account_access WHERE deleted_at IS NULL`, func(rs *sql.Rows) error {
		var k [3]int64
		err := rs.Scan(&k[0], &k[1], &k[2])
		w.shares[k] = true
		return err
	})
	return w
}

// canAccess: the account is live and the actor owns it or holds a live
// share of it from its owner.
func (w modelWorld) canAccess(actor, account int64) bool {
	a, ok := w.accounts[account]
	if !ok || a.deleted {
		return false
	}
	return a.owner == actor || w.shares[[3]int64{a.owner, actor, account}]
}

type modelRequest struct {
	scheduled       bool // POST /api/scheduled/batch-update
	actor           int64
	malformed       bool
	raw             string // the body when malformed
	ids             []int64
	desc            *string
	cat             *int64
	updateSchedules bool
	explicitNulls   bool // send absent fields as JSON null
}

func (r modelRequest) path() string {
	if r.scheduled {
		return pathSchedBatch
	}
	return pathTxnBatch
}

func (r modelRequest) body() string {
	if r.malformed {
		return r.raw
	}
	m := map[string]any{"ids": r.ids}
	if r.desc != nil {
		m["description"] = *r.desc
	} else if r.explicitNulls {
		m["description"] = nil
	}
	if r.cat != nil {
		m["category_id"] = *r.cat
	} else if r.explicitNulls {
		m["category_id"] = nil
	}
	if r.updateSchedules {
		m["update_schedules"] = true
	}
	b, _ := json.Marshal(m)
	return string(b)
}

type modelEdit struct {
	desc *string
	cat  int64
}

type modelOutcome struct {
	status     int
	msg        string
	counts     map[string]int
	txnEdits   map[int64]modelEdit
	schedEdits map[int64]modelEdit
}

// predictBatchEdit is the contract's rule table, checked in order.
func predictBatchEdit(w modelWorld, r modelRequest) modelOutcome {
	reject := func(status int, msg string) modelOutcome { return modelOutcome{status: status, msg: msg} }

	// 1-7: the request alone.
	if r.malformed {
		return reject(400, "invalid request body")
	}
	if len(r.ids) == 0 {
		return reject(400, "ids required")
	}
	if r.desc == nil && r.cat == nil {
		return reject(400, "nothing to update: set description and/or category_id")
	}
	for _, id := range r.ids {
		if id <= 0 {
			return reject(400, "invalid id")
		}
	}
	var distinct []int64
	seen := map[int64]bool{}
	for _, id := range r.ids {
		if !seen[id] {
			seen[id] = true
			distinct = append(distinct, id)
		}
	}
	if len(distinct) > 5000 {
		return reject(400, "too many ids (max 5000)")
	}
	var newDesc *string
	if r.desc != nil {
		d := strings.TrimSpace(*r.desc)
		if d == "" {
			return reject(400, "description must not be empty")
		}
		if len(d) > 500 {
			return reject(400, "description must not exceed 500 characters")
		}
		newDesc = &d
	}
	if r.cat != nil && *r.cat <= 0 {
		return reject(400, "invalid category_id")
	}

	// 8: live rows; the rest is skipped.
	table := w.txns
	if r.scheduled {
		table = w.scheds
	}
	var live []modelRow
	for _, id := range distinct {
		if row, ok := table[id]; ok && !row.deleted {
			live = append(live, row)
		}
	}
	out := modelOutcome{status: 200, txnEdits: map[int64]modelEdit{}, schedEdits: map[int64]modelEdit{}}
	if r.scheduled {
		out.counts = map[string]int{"updated": 0, "unchanged": 0, "skipped": len(distinct) - len(live)}
	} else {
		out.counts = map[string]int{
			"updated": 0, "unchanged": 0, "skipped": len(distinct) - len(live),
			"schedules_updated": 0, "schedules_unchanged": 0, "schedules_skipped": 0,
		}
	}
	if len(live) == 0 {
		return out
	}

	// 9: every account accessible.
	for _, row := range live {
		if !w.canAccess(r.actor, row.account) {
			return reject(403, "forbidden: no shared access")
		}
	}

	// 10: category rules.
	owner := live[0].owner
	var catType string
	if r.cat != nil {
		for _, row := range live {
			if row.owner != owner {
				return reject(400, "selected entries belong to different owners; change the category for one owner at a time")
			}
		}
		c, ok := w.cats[*r.cat]
		if !ok || c.deleted || c.owner != owner {
			return reject(400, "category not found")
		}
		for _, row := range live {
			if row.typ != live[0].typ {
				return reject(400, "selected entries mix income and expense; change the category for one type at a time")
			}
		}
		if live[0].typ != c.typ {
			return reject(400, "category type does not match the selected entries")
		}
		catType = c.typ
	}

	// 11: linked schedules.
	var linked []modelRow
	if !r.scheduled && r.updateSchedules {
		seenSched := map[int64]bool{}
		for _, row := range live {
			if row.sched == 0 || seenSched[row.sched] {
				continue
			}
			seenSched[row.sched] = true
			s, ok := w.scheds[row.sched]
			if !ok || s.deleted || !w.canAccess(r.actor, s.account) {
				out.counts["schedules_skipped"]++
				continue
			}
			linked = append(linked, s)
		}
		if r.cat != nil {
			for _, s := range linked {
				if s.owner != owner {
					return reject(400, "a linked recurring schedule belongs to a different owner")
				}
			}
			for _, s := range linked {
				if s.typ != catType {
					return reject(400, "a linked recurring schedule has a different type than the new category")
				}
			}
		}
	}

	// 12: only real changes are written.
	edit := func(row modelRow) (modelEdit, bool) {
		e := modelEdit{desc: row.desc, cat: row.cat}
		if newDesc != nil {
			e.desc = newDesc
		}
		if r.cat != nil {
			e.cat = *r.cat
		}
		sameDesc := (e.desc == nil) == (row.desc == nil) && (e.desc == nil || *e.desc == *row.desc)
		return e, !sameDesc || e.cat != row.cat
	}
	rowEdits := out.txnEdits
	if r.scheduled {
		rowEdits = out.schedEdits
	}
	for _, row := range live {
		if e, changed := edit(row); changed {
			rowEdits[row.id] = e
			out.counts["updated"]++
		} else {
			out.counts["unchanged"]++
		}
	}
	for _, s := range linked {
		if e, changed := edit(s); changed {
			out.schedEdits[s.id] = e
			out.counts["schedules_updated"]++
		} else {
			out.counts["schedules_unchanged"]++
		}
	}
	return out
}

// verifyModelEdits checks a 200 against the model: only the predicted rows
// changed, only in editable columns, to the predicted values, each with a
// fresh sync_version, and sync_meta moved once per written row.
func verifyModelEdits(t *testing.T, label string, before, after dbSnapshot, want modelOutcome) {
	t.Helper()
	cells, rows := diffSnapshots(before, after)
	if len(rows) > 0 {
		t.Fatalf("%s\nrows appeared or vanished:\n%s", label, formatDiff(nil, rows))
	}
	expected := map[string]map[int64]modelEdit{"transactions": want.txnEdits, "scheduled_transactions": want.schedEdits}
	bumped := map[string]map[int64]bool{"transactions": {}, "scheduled_transactions": {}}
	for _, c := range cells {
		if c.table == "sync_meta" && c.col == "version" {
			continue
		}
		edits, ok := expected[c.table]
		if !ok {
			t.Fatalf("%s\nunexpected change outside the edited tables: %s", label, c)
		}
		if _, ok := edits[c.id]; !ok {
			t.Fatalf("%s\nrow the model did not expect to change: %s", label, c)
		}
		if !editableColumns[c.col] {
			t.Fatalf("%s\nnon-editable column changed: %s", label, c)
		}
		if c.col == "sync_version" {
			bumped[c.table][c.id] = true
		}
	}
	oldVersion := mustAtoi(t, before["sync_meta"][1]["version"])
	for table, edits := range expected {
		for id, e := range edits {
			row := after[table][id]
			wantDesc := "NULL"
			if e.desc != nil {
				wantDesc = sqlQuote(*e.desc)
			}
			if row["description"] != wantDesc || row["category_id"] != sqlInt(e.cat) {
				t.Fatalf("%s\n%s#%d = (%s, %s), model predicts (%s, %d)", label, table, id,
					row["description"], row["category_id"], wantDesc, e.cat)
			}
			if !bumped[table][id] || mustAtoi(t, row["sync_version"]) <= oldVersion {
				t.Fatalf("%s\n%s#%d: sync_version %s not bumped above %d", label, table, id, row["sync_version"], oldVersion)
			}
		}
	}
	delta := mustAtoi(t, after["sync_meta"][1]["version"]) - oldVersion
	if want := int64(len(want.txnEdits) + len(want.schedEdits)); delta != want {
		t.Fatalf("%s\nsync_meta moved by %d, want %d", label, delta, want)
	}
}

// ===== Request generator =====

func randomBatchRequest(rng *rand.Rand, w modelWorld, f *batchFix) modelRequest {
	r := modelRequest{scheduled: rng.IntN(5) == 0, actor: f.adminID, updateSchedules: rng.IntN(2) == 0, explicitNulls: rng.IntN(4) == 0}
	if rng.IntN(2) == 0 {
		r.actor = f.guestID
	}
	if rng.IntN(30) == 0 {
		malformed := []string{"", "not json", "[]", `{"ids":"1","description":"x"}`, `{"ids":[1.5],"description":"x"}`, `{"ids":[1],"category_id":"7"}`}
		r.malformed, r.raw = true, malformed[rng.IntN(len(malformed))]
		return r
	}

	table, all := w.txns, w.txnIDs
	if r.scheduled {
		table, all = w.scheds, w.schedIDs
	}
	// The id pool: everything, one account's rows, or the actor's reachable rows.
	pool := all
	switch rng.IntN(3) {
	case 0:
		acct := table[all[rng.IntN(len(all))]].account
		pool = nil
		for _, id := range all {
			if table[id].account == acct {
				pool = append(pool, id)
			}
		}
	case 1:
		var reachable []int64
		for _, id := range all {
			if w.canAccess(r.actor, table[id].account) {
				reachable = append(reachable, id)
			}
		}
		if len(reachable) > 0 {
			pool = reachable
		}
	}
	n := 1 + rng.IntN(4)
	switch rng.IntN(12) {
	case 0:
		n = 0
	case 1:
		n = 5 + rng.IntN(10)
	}
	for i := 0; i < n; i++ {
		r.ids = append(r.ids, pool[rng.IntN(len(pool))])
	}
	if n > 0 && rng.IntN(6) == 0 {
		r.ids = append(r.ids, 900000+int64(rng.IntN(1000))) // missing
	}
	if rng.IntN(40) == 0 {
		r.ids = append(r.ids, -int64(rng.IntN(2))) // 0 or -1
	}

	pickStr := func(xs ...string) *string { s := xs[rng.IntN(len(xs))]; return &s }
	switch k := rng.IntN(20); {
	case k < 8: // unchanged
	case k < 17:
		r.desc = pickStr("Stipendio", "stipendio", "  Stipendio  ", "Stipendio Paolo", "Rent", "Coffee", "è", "it's",
			strings.Repeat("x", 500), strings.Repeat("é", 250))
	case k < 19:
		r.desc = pickStr("", "   ", "\t\n")
	default:
		r.desc = pickStr(strings.Repeat("a", 501), strings.Repeat("è", 251), " "+strings.Repeat("b", 501))
	}

	pickCat := func(c int64) *int64 { return &c }
	switch k := rng.IntN(20); {
	case k < 8: // unchanged
	case k < 13:
		// A live category of the first selected row's owner and type, so
		// category edits succeed often enough to matter.
		if len(r.ids) > 0 {
			if row, ok := table[r.ids[0]]; ok {
				var fits []int64
				for _, id := range w.catIDs {
					if c := w.cats[id]; c.owner == row.owner && c.typ == row.typ && !c.deleted {
						fits = append(fits, id)
					}
				}
				if len(fits) > 0 {
					r.cat = pickCat(fits[rng.IntN(len(fits))])
				}
			}
		}
	case k < 18:
		r.cat = pickCat(w.catIDs[rng.IntN(len(w.catIDs))])
	case k < 19:
		r.cat = pickCat(999999)
	default:
		r.cat = pickCat(-int64(rng.IntN(2)))
	}
	return r
}
