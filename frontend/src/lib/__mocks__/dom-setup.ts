// jsdom does not implement the browser's dialog methods. Visibility is enough
// for component tests; native focus containment needs manual browser validation.
HTMLDialogElement.prototype.showModal = function () { this.open = true; };
HTMLDialogElement.prototype.close = function () { this.open = false; };
