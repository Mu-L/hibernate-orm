/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * Copyright (c) 2010, Red Hat Inc. or third-party contributors as
 * indicated by the @author tags or express copyright attribution
 * statements applied by the authors.  All third-party contributions are
 * distributed under license by Red Hat Inc.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, write to:
 * Free Software Foundation, Inc.
 * 51 Franklin Street, Fifth Floor
 * Boston, MA  02110-1301  USA
 */
package org.hibernate.engine.spi;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

import org.hibernate.CustomEntityDirtinessStrategy;
import org.hibernate.HibernateException;
import org.hibernate.LockMode;
import org.hibernate.Session;
import org.hibernate.bytecode.instrumentation.spi.FieldInterceptor;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.persister.entity.UniqueKeyLoadable;
import org.hibernate.pretty.MessageHelper;

/**
 * We need an entry to tell us all about the current state of an object with respect to its persistent state
 * 
 * @author Gavin King
 */
public final class EntityEntry implements Serializable {
	private LockMode lockMode;
	private Status status;
	private Status previousStatus;
	private final Serializable id;
	private Object[] loadedState;
	private Object[] deletedState;
	private boolean existsInDatabase;
	private Object version;
	private transient EntityPersister persister; // for convenience to save some lookups
	private final String entityName;
	private transient EntityKey cachedEntityKey; // cached EntityKey (lazy-initialized)
	private boolean isBeingReplicated;
	private boolean loadedWithLazyPropertiesUnfetched; //NOTE: this is not updated when properties are fetched lazily!
	private final transient Object rowId;
	private final transient PersistenceContext persistenceContext;

	public EntityEntry(
			final Status status,
			final Object[] loadedState,
			final Object rowId,
			final Serializable id,
			final Object version,
			final LockMode lockMode,
			final boolean existsInDatabase,
			final EntityPersister persister,
			final boolean disableVersionIncrement,
			final boolean lazyPropertiesAreUnfetched,
			final PersistenceContext persistenceContext) {
		this.status = status;
		this.previousStatus = null;
		// only retain loaded state if the status is not Status.READ_ONLY
		if ( status != Status.READ_ONLY ) {
			this.loadedState = loadedState;
		}
		this.id=id;
		this.rowId=rowId;
		this.existsInDatabase=existsInDatabase;
		this.version=version;
		this.lockMode=lockMode;
		this.isBeingReplicated=disableVersionIncrement;
		this.loadedWithLazyPropertiesUnfetched = lazyPropertiesAreUnfetched;
		this.persister=persister;
		this.entityName = persister == null ? null : persister.getEntityName();
		this.persistenceContext = persistenceContext;
	}

	/**
	 * This for is used during custom deserialization handling
	 */
	@SuppressWarnings( {"JavaDoc"})
	private EntityEntry(
			final SessionFactoryImplementor factory,
			final String entityName,
			final Serializable id,
			final Status status,
			final Status previousStatus,
			final Object[] loadedState,
			final Object[] deletedState,
			final Object version,
			final LockMode lockMode,
			final boolean existsInDatabase,
			final boolean isBeingReplicated,
			final boolean loadedWithLazyPropertiesUnfetched,
			final PersistenceContext persistenceContext) {
		this.entityName = entityName;
		this.persister = ( factory == null ? null : factory.getEntityPersister( entityName ) );
		this.id = id;
		this.status = status;
		this.previousStatus = previousStatus;
		this.loadedState = loadedState;
		this.deletedState = deletedState;
		this.version = version;
		this.lockMode = lockMode;
		this.existsInDatabase = existsInDatabase;
		this.isBeingReplicated = isBeingReplicated;
		this.loadedWithLazyPropertiesUnfetched = loadedWithLazyPropertiesUnfetched;
		this.rowId = null; // this is equivalent to the old behavior...
		this.persistenceContext = persistenceContext;
	}

	public LockMode getLockMode() {
		return lockMode;
	}

	public void setLockMode(LockMode lockMode) {
		this.lockMode = lockMode;
	}

	public Status getStatus() {
		return status;
	}

	public void setStatus(Status status) {
		if (status==Status.READ_ONLY) {
			loadedState = null; //memory optimization
		}
		if ( this.status != status ) {
			this.previousStatus = this.status;
			this.status = status;
		}
	}

	public Serializable getId() {
		return id;
	}

	public Object[] getLoadedState() {
		return loadedState;
	}

	public Object[] getDeletedState() {
		return deletedState;
	}

	public void setDeletedState(Object[] deletedState) {
		this.deletedState = deletedState;
	}

	public boolean isExistsInDatabase() {
		return existsInDatabase;
	}

	public Object getVersion() {
		return version;
	}

	public EntityPersister getPersister() {
		return persister;
	}

	/**
	 * Get the EntityKey based on this EntityEntry.
	 * @return the EntityKey
	 * @throws  IllegalStateException if getId() is null
	 */
	public EntityKey getEntityKey() {
		if ( cachedEntityKey == null ) {
			if ( getId() == null ) {
				throw new IllegalStateException( "cannot generate an EntityKey when id is null.");
			}
			cachedEntityKey = new EntityKey( getId(), getPersister() );
		}
		return cachedEntityKey;
	}

	public String getEntityName() {
		return entityName;
	}

	public boolean isBeingReplicated() {
		return isBeingReplicated;
	}

	public Object getRowId() {
		return rowId;
	}

	/**
	 * Handle updating the internal state of the entry after actually performing
	 * the database update.  Specifically we update the snapshot information and
	 * escalate the lock mode
	 *
	 * @param entity The entity instance
	 * @param updatedState The state calculated after the update (becomes the
	 * new {@link #getLoadedState() loaded state}.
	 * @param nextVersion The new version.
	 */
	public void postUpdate(Object entity, Object[] updatedState, Object nextVersion) {
		this.loadedState = updatedState;
		setLockMode( LockMode.WRITE );

		if ( getPersister().isVersioned() ) {
			this.version = nextVersion;
			getPersister().setPropertyValue( entity, getPersister().getVersionProperty(), nextVersion );
		}

		if ( getPersister().getInstrumentationMetadata().isInstrumented() ) {
			final FieldInterceptor interceptor = getPersister().getInstrumentationMetadata().extractInterceptor( entity );
			if ( interceptor != null ) {
				interceptor.clearDirty();
			}
		}
		if( entity instanceof SelfDirtinessTracker)
			((SelfDirtinessTracker) entity).$$_hibernate_clearDirtyAttributes();

		persistenceContext.getSession()
				.getFactory()
				.getCustomEntityDirtinessStrategy()
				.resetDirty( entity, getPersister(), (Session) persistenceContext.getSession() );
	}

	/**
	 * After actually deleting a row, record the fact that the instance no longer
	 * exists in the database
	 */
	public void postDelete() {
		previousStatus = status;
		status = Status.GONE;
		existsInDatabase = false;
	}

	/**
	 * After actually inserting a row, record the fact that the instance exists on the 
	 * database (needed for identity-column key generation)
	 */
	public void postInsert(Object[] insertedState) {
		existsInDatabase = true;
	}

	public boolean isNullifiable(boolean earlyInsert, SessionImplementor session) {
		return getStatus() == Status.SAVING || (
				earlyInsert ?
						!isExistsInDatabase() :
						session.getPersistenceContext().getNullifiableEntityKeys()
							.contains( getEntityKey() )
				);
	}

	public Object getLoadedValue(String propertyName) {
		if ( loadedState == null || propertyName == null ) {
			return null;
		}
		else {
			int propertyIndex = ( (UniqueKeyLoadable) persister )
					.getPropertyIndex( propertyName );
			return loadedState[propertyIndex];
		}
	}

	/**
	 * Not sure this is the best method name, but the general idea here is to return {@code true} if the entity can
	 * possibly be dirty.  This can only be the case if it is in a modifiable state (not read-only/deleted) and it
	 * either has mutable properties or field-interception is not telling us it is dirty.  Clear as mud? :/
	 *
	 * A name like canPossiblyBeDirty might be better
	 *
	 * @param entity The entity to test
	 *
	 * @return {@code true} indicates that the entity could possibly be dirty and that dirty check
	 * should happen; {@code false} indicates there is no way the entity can be dirty
	 */
	public boolean requiresDirtyCheck(Object entity) {
		return isModifiableEntity()
				&& ( ! isUnequivocallyNonDirty( entity ) );
	}

	@SuppressWarnings( {"SimplifiableIfStatement"})
	private boolean isUnequivocallyNonDirty(Object entity) {

		if(entity instanceof SelfDirtinessTracker)
			return ((SelfDirtinessTracker) entity).$$_hibernate_hasDirtyAttributes();

		final CustomEntityDirtinessStrategy customEntityDirtinessStrategy =
				persistenceContext.getSession().getFactory().getCustomEntityDirtinessStrategy();
		if ( customEntityDirtinessStrategy.canDirtyCheck( entity, getPersister(), (Session) persistenceContext.getSession() ) ) {
			return ! customEntityDirtinessStrategy.isDirty( entity, getPersister(), (Session) persistenceContext.getSession() );
		}

		if ( getPersister().hasMutableProperties() ) {
			return false;
		}

		if ( getPersister().getInstrumentationMetadata().isInstrumented() ) {
			// the entity must be instrumented (otherwise we cant check dirty flag) and the dirty flag is false
			return ! getPersister().getInstrumentationMetadata().extractInterceptor( entity ).isDirty();
		}

		return false;
	}

	/**
	 * Can the entity be modified?
	 *
	 * The entity is modifiable if all of the following are true:
	 * <ul>
	 * <li>the entity class is mutable</li>
	 * <li>the entity is not read-only</li>
	 * <li>if the current status is Status.DELETED, then the entity was not read-only when it was deleted</li>
	 * </ul>
	 * @return true, if the entity is modifiable; false, otherwise,
	 */
	public boolean isModifiableEntity() {
		return getPersister().isMutable()
				&& status != Status.READ_ONLY
				&& ! ( status == Status.DELETED && previousStatus == Status.READ_ONLY );
	}

	public void forceLocked(Object entity, Object nextVersion) {
		version = nextVersion;
		loadedState[ persister.getVersionProperty() ] = version;
		//noinspection deprecation
		setLockMode( LockMode.FORCE );  // TODO:  use LockMode.PESSIMISTIC_FORCE_INCREMENT
		persister.setPropertyValue( entity, getPersister().getVersionProperty(), nextVersion );
	}

	public boolean isReadOnly() {
		if (status != Status.MANAGED && status != Status.READ_ONLY) {
			throw new HibernateException("instance was not in a valid state");
		}
		return status == Status.READ_ONLY;
	}

	public void setReadOnly(boolean readOnly, Object entity) {
		if ( readOnly == isReadOnly() ) {
			// simply return since the status is not being changed
			return;
		}
		if ( readOnly ) {
			setStatus( Status.READ_ONLY );
			loadedState = null;
		}
		else {
			if ( ! persister.isMutable() ) {
				throw new IllegalStateException( "Cannot make an immutable entity modifiable." );
			}
			setStatus( Status.MANAGED );
			loadedState = getPersister().getPropertyValues( entity );
			persistenceContext.getNaturalIdHelper().manageLocalNaturalIdCrossReference(
					persister,
					id,
					loadedState,
					null,
					CachedNaturalIdValueSource.LOAD
			);
		}
	}

	public String toString() {
		return "EntityEntry" + 
				MessageHelper.infoString(entityName, id) + 
				'(' + status + ')';
	}

	public boolean isLoadedWithLazyPropertiesUnfetched() {
		return loadedWithLazyPropertiesUnfetched;
	}

	/**
	 * Custom serialization routine used during serialization of a
	 * Session/PersistenceContext for increased performance.
	 *
	 * @param oos The stream to which we should write the serial data.
	 *
	 * @throws IOException If a stream error occurs
	 */
	public void serialize(ObjectOutputStream oos) throws IOException {
		oos.writeObject( entityName );
		oos.writeObject( id );
		oos.writeObject( status.name() );
		oos.writeObject( (previousStatus == null ? "" : previousStatus.name()) );
		// todo : potentially look at optimizing these two arrays
		oos.writeObject( loadedState );
		oos.writeObject( deletedState );
		oos.writeObject( version );
		oos.writeObject( lockMode.toString() );
		oos.writeBoolean( existsInDatabase );
		oos.writeBoolean( isBeingReplicated );
		oos.writeBoolean( loadedWithLazyPropertiesUnfetched );
	}

	/**
	 * Custom deserialization routine used during deserialization of a
	 * Session/PersistenceContext for increased performance.
	 *
	 * @param ois The stream from which to read the entry.
	 * @param persistenceContext The context being deserialized.
	 *
	 * @return The deserialized EntityEntry
	 *
	 * @throws IOException If a stream error occurs
	 * @throws ClassNotFoundException If any of the classes declared in the stream
	 * cannot be found
	 */
	public static EntityEntry deserialize(
			ObjectInputStream ois,
			PersistenceContext persistenceContext) throws IOException, ClassNotFoundException {
		String previousStatusString;
		return new EntityEntry(
				// this complexity comes from non-flushed changes, should really look at how that reattaches entries
				( persistenceContext.getSession() == null ? null : persistenceContext.getSession().getFactory() ),
				(String) ois.readObject(),
				( Serializable ) ois.readObject(),
				Status.valueOf( (String) ois.readObject() ),
				( ( previousStatusString = ( String ) ois.readObject() ).length() == 0 ?
							null :
							Status.valueOf( previousStatusString )
				),
				( Object[] ) ois.readObject(),
				( Object[] ) ois.readObject(),
				ois.readObject(),
				LockMode.valueOf( (String) ois.readObject() ),
				ois.readBoolean(),
				ois.readBoolean(),
				ois.readBoolean(),
				persistenceContext
		);
	}
}
