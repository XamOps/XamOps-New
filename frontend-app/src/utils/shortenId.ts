/**
 * Shortens a long id from the middle.
 *
 * <p>The ends of an ARN carry the identity — the service at the front, the resource name at the
 * back. Truncating with a trailing ellipsis throws away the half that names the thing.
 *
 * <p>Extracted from the Cloud Map connectivity canvas (34 characters, a fixed-width card) and the
 * cost resource list (72, a full-width row). Each call site passes its own budget, which is the
 * only thing that differed between the two copies — so a shared version loses nothing.
 *
 * @param id  the identifier to shorten; returned unchanged when it already fits
 * @param max how many characters the result may occupy, ellipsis included
 */
export const shortenId = (id: string, max = 34): string => {
  if (id.length <= max) return id;
  const head = Math.ceil((max - 1) / 2);
  return `${id.slice(0, head)}…${id.slice(id.length - (max - head - 1))}`;
};
