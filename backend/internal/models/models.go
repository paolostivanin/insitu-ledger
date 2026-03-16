package models

import "time"

type User struct {
	ID           int64     `json:"id"`
	Email        string    `json:"email"`
	Name         string    `json:"name"`
	PasswordHash string    `json:"-"`
	CreatedAt    time.Time `json:"created_at"`
	UpdatedAt    time.Time `json:"updated_at"`
	SyncVersion  int64     `json:"sync_version"`
}

type Category struct {
	ID          int64      `json:"id"`
	UserID      int64      `json:"user_id"`
	ParentID    *int64     `json:"parent_id,omitempty"`
	Name        string     `json:"name"`
	Type        string     `json:"type"` // "income" or "expense"
	Icon        *string    `json:"icon,omitempty"`
	Color       *string    `json:"color,omitempty"`
	CreatedAt   time.Time  `json:"created_at"`
	UpdatedAt   time.Time  `json:"updated_at"`
	DeletedAt   *time.Time `json:"deleted_at,omitempty"`
	SyncVersion int64      `json:"sync_version"`
}

type Account struct {
	ID          int64      `json:"id"`
	UserID      int64      `json:"user_id"`
	Name        string     `json:"name"`
	Currency    string     `json:"currency"`
	Balance     float64    `json:"balance"`
	CreatedAt   time.Time  `json:"created_at"`
	UpdatedAt   time.Time  `json:"updated_at"`
	DeletedAt   *time.Time `json:"deleted_at,omitempty"`
	SyncVersion int64      `json:"sync_version"`
}

type Transaction struct {
	ID          int64      `json:"id"`
	AccountID   int64      `json:"account_id"`
	CategoryID  int64      `json:"category_id"`
	UserID      int64      `json:"user_id"`
	Type        string     `json:"type"` // "income" or "expense"
	Amount      float64    `json:"amount"`
	Currency    string     `json:"currency"`
	Description *string    `json:"description,omitempty"`
	Date        string     `json:"date"` // YYYY-MM-DD
	CreatedAt   time.Time  `json:"created_at"`
	UpdatedAt   time.Time  `json:"updated_at"`
	DeletedAt   *time.Time `json:"deleted_at,omitempty"`
	SyncVersion int64      `json:"sync_version"`
}

type ScheduledTransaction struct {
	ID             int64      `json:"id"`
	AccountID      int64      `json:"account_id"`
	CategoryID     int64      `json:"category_id"`
	UserID         int64      `json:"user_id"`
	Type           string     `json:"type"`
	Amount         float64    `json:"amount"`
	Currency       string     `json:"currency"`
	Description    *string    `json:"description,omitempty"`
	RRule          string     `json:"rrule"`
	NextOccurrence string     `json:"next_occurrence"` // YYYY-MM-DD
	Active         bool       `json:"active"`
	CreatedAt      time.Time  `json:"created_at"`
	UpdatedAt      time.Time  `json:"updated_at"`
	DeletedAt      *time.Time `json:"deleted_at,omitempty"`
	SyncVersion    int64      `json:"sync_version"`
}

type SharedAccess struct {
	ID          int64     `json:"id"`
	OwnerUserID int64     `json:"owner_user_id"`
	GuestUserID int64     `json:"guest_user_id"`
	Permission  string    `json:"permission"` // "read" or "write"
	CreatedAt   time.Time `json:"created_at"`
	SyncVersion int64     `json:"sync_version"`
}

// SyncRequest is sent by mobile clients to fetch changes since a given version.
type SyncRequest struct {
	SinceVersion int64 `json:"since_version"`
}

// SyncResponse contains all rows changed since the requested version.
type SyncResponse struct {
	CurrentVersion        int64                   `json:"current_version"`
	Transactions          []Transaction           `json:"transactions"`
	Categories            []Category              `json:"categories"`
	Accounts              []Account               `json:"accounts"`
	ScheduledTransactions []ScheduledTransaction   `json:"scheduled_transactions"`
}
