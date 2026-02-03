## 2025-05-14 - [Input Animation Pattern]
**Learning:** The application's "Input Effects" JavaScript (used for floating labels) relies on strict DOM proximity between the input and its label (`element.nextElementSibling`). Wrapping inputs in container divs (e.g., for password toggles) breaks this animation unless the script is updated to traverse from the container.
**Action:** When adding icons or toggles to animated inputs, ensure the "Input Effects" logic is updated to check if the input is nested and adjust the sibling traversal accordingly.
