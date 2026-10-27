import { FormEvent, useState } from 'react';
import { useOperator } from '../hooks/useOperator';

export function OperatorControl() {
  const [name, setName] = useOperator();
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState('');

  const begin = () => {
    setDraft(name);
    setEditing(true);
  };
  const save = (e: FormEvent) => {
    e.preventDefault();
    if (draft.trim()) setName(draft);
    setEditing(false);
  };

  if (editing) {
    return (
      <form className="operator" onSubmit={save}>
        <label htmlFor="operator-name" className="visually-hidden">
          Your name
        </label>
        <input id="operator-name" value={draft} onChange={(e) => setDraft(e.target.value)} autoFocus maxLength={100} />
        <button type="submit" className="btn btn-quiet">
          Save
        </button>
      </form>
    );
  }
  return (
    <div className="operator">
      <span>Resolving as {name || 'nobody yet'}</span>
      <button type="button" className="btn btn-quiet" onClick={begin}>
        {name ? 'Change' : 'Set your name'}
      </button>
    </div>
  );
}
