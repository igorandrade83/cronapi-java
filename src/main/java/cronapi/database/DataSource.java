package cronapi.database;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import javax.persistence.EntityManager;
import javax.persistence.Parameter;
import javax.persistence.TypedQuery;
import javax.persistence.metamodel.EntityType;
import javax.persistence.metamodel.SingularAttribute;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializable;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;

import cronapi.Utils;
import cronapi.Var;

/**
 * Class database manipulation, responsible for querying, inserting,
 * updating and deleting database data procedurally, allowing paged
 * navigation and setting page size.
 *
 * @author robson.ataide
 * @version 1.0
 * @since 2017-04-26
 *
 */
public class DataSource implements JsonSerializable {

	private String entity;
  private String simpleEntity;
	private Class domainClass;
	private String filter;
	private Var[] params;
	private int pageSize;
	private Page page;
	private int index;
	private int current;
	private JpaRepository<Object, String> repository;
	private Pageable pageRequest;
	private Object insertedElement = null;

	/**
	 * Init a datasource with a page size equals 100
	 *
	 * @param entity - full name of entitiy class like String
	 */
	public DataSource(String entity) {
		this(entity, 100);
	}

	/**
	 * Init a datasource setting a page size
	*
	* @param entity - full name of entitiy class like String
	* @param pageSize - page size of a Pageable object retrieved from repository
	*/
	public DataSource(String entity, int pageSize) {
		this.entity = entity;
		this.simpleEntity = entity.substring(entity.lastIndexOf(".")+1);
		this.pageSize = pageSize;
		this.pageRequest = new PageRequest(0, pageSize);

		//initialize dependencies and necessaries objects
		this.instantiateRepository();
	}

	/**
	 * Retrieve repository from entity
	 *
	 * @throws RuntimeException when repository not fount, entity passed not found or cast repository
	 */
	private void instantiateRepository() {
		try {
			domainClass = Class.forName(this.entity);
			this.repository = TransactionManager.findRepository(domainClass);
		} catch (ClassNotFoundException cnfex) {
			throw new RuntimeException(cnfex);
		}
	}

	/**
	 * Retrieve objects from database using repository when filter is null or empty,
	 * if filter not null or is not empty, this method uses entityManager and create a
	 * jpql instruction.
	 *
	 * @return a array of Object
	 */
	public Object[] fetch() {
		if (this.filter != null && !"".equals(this.filter)) {
			try {
				EntityManager em = TransactionManager.getEntityManager(domainClass);
				TypedQuery<?> query = em.createQuery(filter, domainClass);

				int i = 0;
        Parameter<?>[] queryParams = query.getParameters().toArray(new Parameter<?>[query.getParameters().size()]);
				for (Var p : this.params) {
				  if (p.getId() != null) {
            query.setParameter(p.getId(), p.getObject(queryParams[i].getParameterType()));
          } else {
				    if (i <= queryParams.length-1) {
              query.setParameter(queryParams[i].getName(), p.getObject(queryParams[i].getParameterType()));
            }
          }
          i++;
				}

				query.setFirstResult(this.pageRequest.getPageNumber() * this.pageRequest.getPageSize());
				query.setMaxResults(this.pageRequest.getPageSize());

				List<?> resultsInPage = query.getResultList();

				this.page = new PageImpl(resultsInPage, this.pageRequest, 0);
			} catch (Exception ex) {
				throw new RuntimeException(ex);
			}
		} else
			this.page = this.repository.findAll(this.pageRequest);

		//has data, moves cursor to first position
		if (this.page.getNumberOfElements() > 0)
			this.current = 0;

		return this.page.getContent().toArray();
	}

	public EntityMetadata getMetadata() {
	  return new EntityMetadata(domainClass);
  }

	/**
	 * Create a new instance of entity and add a
	 * results and set current (index) for his position
	 */
	public void insert() {
		try {
			this.insertedElement = this.domainClass.newInstance();
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

  public void insert(Map<?,?> values) {
    try {
      this.insertedElement = this.domainClass.newInstance();
      for (Object key: values.keySet()) {
        updateField(key.toString(), values.get(key));
      }
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }

	/**
	 * Saves the object in the current index or a new object when has insertedElement
	 */
	public Object save() {
		try {
			Object toSave;
			EntityManager em = TransactionManager.getEntityManager(domainClass);
      em.getMetamodel().entity(domainClass);

      if (!em.getTransaction().isActive()) {
        em.getTransaction().begin();
      }

			if (this.insertedElement != null) {
				toSave = this.insertedElement;
				this.insertedElement = null;
				em.persist(toSave);
			} else
				toSave = this.getObject();

			return em.merge(toSave);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

  public void delete(Var[] primaryKeys) {
	  insert();
	  int i = 0;
	  Var[] params = new Var[primaryKeys.length];

	  EntityManager em = TransactionManager.getEntityManager(domainClass);
    EntityType type = em.getMetamodel().entity(domainClass);

	  String jpql = " DELETE FROM "+entity.substring(entity.lastIndexOf(".")+1) + " WHERE ";
	  for (Object obj: type.getAttributes()) {
      SingularAttribute field = (SingularAttribute) obj;
      if (field.isId()) {
        if (i > 0) {
          jpql += " AND ";
        }
        jpql += "" + field.getName() + " = :p" + i;
        params[i] = Var.valueOf("p" + i, primaryKeys[i].getObject(field.getType().getJavaType()));
        i++;
      }
    }

    execute(jpql, params);
  }

	/**
	 * Removes the object in the current index
	 */
	public void delete() {
		try {
			Object toRemove = this.getObject();
			EntityManager em = TransactionManager.getEntityManager(domainClass);
			if (!em.getTransaction().isActive()) {
				em.getTransaction().begin();
			}
			//returns managed instance
			toRemove = em.merge(toRemove);
			em.remove(toRemove);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Update a field from object in the current index
	 *
	 * @param fieldName - attributte name in entity
	 * @param fieldValue - value that replaced or inserted in field name passed
	 */
	public void updateField(String fieldName, Object fieldValue) {
    updateField(getObject(), fieldName, fieldValue);
	}

  private void updateField(Object obj, String fieldName, Object fieldValue) {
    try {
      Method setMethod = Utils.findMethod(obj, "set" + fieldName);
      if (setMethod != null) {
        setMethod.invoke(obj, fieldValue);
      }
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }

	/**
	 * Update fields from object in the current index
	 *
	 * @param fields - bidimensional array like fields
	 * sample: { {"name", "Paul"}, {"age", "21"} }
	 *
	 * @thows RuntimeException if a field is not accessible through a set method
	 */
	public void updateFields(Var... fields) {
		try {
			for (Var field : fields) {
				Method setMethod = Utils.findMethod(getObject(), "set" + field.getId());
				if (setMethod != null) {
					setMethod.invoke(getObject(), field.getObject());
				}
			}
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

  public void filter(Var data, Var[] extraParams) {
	  Map<?, ?> primaryKeys = (Map<?,?>) data.getObject();

    EntityManager em = TransactionManager.getEntityManager(domainClass);
    EntityType type = em.getMetamodel().entity(domainClass);

    int i = 0;
    String jpql = " select e FROM "+entity.substring(entity.lastIndexOf(".")+1) + " e WHERE ";
    Vector<Var> params = new Vector<>();
    for (Object obj: type.getAttributes()) {
      SingularAttribute field = (SingularAttribute) obj;
      if (field.isId()) {
        if (i > 0) {
          jpql += " AND ";
        }
        jpql += "e." + field.getName() + " = :p" + i;
        params.add(Var.valueOf("p" + i, Var.valueOf(primaryKeys.get(field.getName())).getObject(field.getType().getJavaType())));
        i++;
      }
    }

    if (extraParams != null) {
      for (Var p: extraParams) {
        jpql += "e." + p.getId() + " = :p" + i;
        params.add(Var.valueOf("p" + i, p.getObject()));
        i++;
      }
    }

    Var[] arr = params.toArray(new Var[params.size()]);

    filter(jpql, arr);
  }

  public void update(Var data) {
    try {
      Map<?, ?> values = (Map<?,?>) data.getObject();

      for (Object key: values.keySet()) {
        updateField(key.toString(), values.get(key));
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

	/**
	 * Return object in current index
	 *
	 * @return Object from database in current position
	 */
	public Object getObject() {

		if (this.insertedElement != null)
			return this.insertedElement;

		if (this.current < 0)
			return null;

		return this.page.getContent().get(this.current);
	}

	/**
	 * Return field passed from object in current index
	 *
	 * @return Object value of field passed
	 * @thows RuntimeException if a field is not accessible through a set method
	 */
	public Object getObject(String fieldName) {
		try {
			Method getMethod = Utils.findMethod(getObject(), "get" + fieldName);
			if (getMethod != null)
				return getMethod.invoke(getObject());
			return null;
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	/**
	 * Moves the index for next position, in pageable case,
	 * looking for next page and so on
	 */
	public void next() {
		if (this.page.getNumberOfElements() > (this.current + 1))
			this.current++;
		else {
			if (this.page.hasNext()) {
				this.pageRequest = this.page.nextPageable();
				this.fetch();
				this.current = 0;
			} else {
				this.current = -1;
			}
		}
	}

	/**
	 * Verify if can moves the index for next position,
	 * in pageable case, looking for next page and so on
	 *
	 * @return boolean true if has next, false else
	 */
	public boolean hasNext() {
		if (this.page.getNumberOfElements() > (this.current + 1))
			return true;
		else {
			if (this.page.hasNext()) {
				return true;
			} else {
				return false;
			}
		}
	}

	/**
	 * Moves the index for previous position, in pageable case,
	 * looking for next page and so on
	 *
	 * @return boolean true if has previous, false else
	 */
	public boolean previous() {
		if (this.current - 1 >= 0) {
			this.current--;
		} else {
			if (this.page.hasPrevious()) {
				this.pageRequest = this.page.previousPageable();
				this.fetch();
				this.current = this.page.getNumberOfElements() - 1;
			} else {
				return false;
			}
		}
		return true;
	}

	/**
	 * Gets a Pageable object retrieved from repository
	 *
	 * @return pageable from repository, returns null when fetched by filter
	 */
	public Page getPage() {
		return this.page;
	}

	/**
	 * Create a new page request with size passed
	 *
	 * @param pageSize size of page request
	 */
	public void setPageSize(int pageSize) {
		this.pageSize = pageSize;
		this.pageRequest = new PageRequest(0, pageSize);
		this.current = -1;
	}

	/**
   * Fetch objects from database by a filter
   *
   * @param filter jpql instruction like a namedQuery
   * @param params parameters used in jpql instruction
   */
  public void filter(String filter, Var... params) {
    this.filter = filter;
    this.params = params;
    this.pageRequest = new PageRequest(0, pageSize);
    this.current = -1;
    this.fetch();
  }

  /**
   * Fetch objects from database by a filter
   *
   * @param filter jpql instruction like a namedQuery
   * @param pageRequest Page
   * @param params parameters used in jpql instruction
   */
  public void filter(String filter, PageRequest pageRequest, Var... params) {
    if (filter == null && params.length > 0) {
      EntityManager em = TransactionManager.getEntityManager(domainClass);
      EntityType type =  em.getMetamodel().entity(domainClass);

      int i = 0;
      String jpql = "Select e from " + simpleEntity + " e where ";
      for (Object obj : type.getAttributes()) {
        SingularAttribute field = (SingularAttribute) obj;
        if (field.isId()) {
          if (i > 0) {
            jpql += " and ";
          }
          jpql += "e." + field.getName() + " = :p" + i;
          params[i].setId("p" + i);
        }
      }

      filter = jpql;

    }

    this.params = params;
    this.filter = filter;
    this.pageRequest = pageRequest;
    this.current = -1;
    this.fetch();
  }

  private Class forName(String name) {
    try {
      return Class.forName(name);
    } catch (ClassNotFoundException e) {
      return null;
    }
  }

  private Object newInstance(String name) {
    try {
      return Class.forName(name).newInstance();
    } catch (Exception e) {
      return null;
    }
  }

  public void deleteRelation(String refId, Var[] primaryKeys, Var[] relationKeys) {
    EntityMetadata metadata = getMetadata();
    RelationMetadata relationMetadata = metadata.getRelations().get(refId);

    EntityManager em = TransactionManager.getEntityManager(domainClass);
    int i = 0;

    String jpql = null;

    Var[] params = null;
    if (relationMetadata.getAssossiationName() != null) {
      params = new Var[relationKeys.length + primaryKeys.length];

      jpql = " DELETE FROM " + relationMetadata.gettAssossiationSimpleName() + " WHERE ";
      EntityType type = em.getMetamodel().entity(domainClass);
      for (Object obj : type.getAttributes()) {
        SingularAttribute field = (SingularAttribute) obj;
        if (field.isId()) {
          if (i > 0) {
            jpql += " AND ";
          }
          jpql += relationMetadata.getAssociationAttribute().getName() + "." + field.getName() + " = :p" + i;
          params[i] = Var.valueOf("p" + i, primaryKeys[i].getObject(field.getType().getJavaType()));
          i++;
        }
      }

      int v = 0;
      type = em.getMetamodel().entity(forName(relationMetadata.getAssossiationName()));
      for (Object obj : type.getAttributes()) {
        SingularAttribute field = (SingularAttribute) obj;
        if (field.isId()) {
          if (i > 0) {
            jpql += " AND ";
          }
          jpql += relationMetadata.getAttribute().getName() + "." + field.getName() + " = :p" + i;
          params[i] = Var.valueOf("p" + i, relationKeys[v].getObject(field.getType().getJavaType()));
          i++;
          v++;
        }
      }

    } else {
      params = new Var[relationKeys.length];

      jpql = " DELETE FROM " + relationMetadata.getSimpleName() + " WHERE ";
      EntityType type = em.getMetamodel().entity(forName(relationMetadata.getName()));
      for (Object obj : type.getAttributes()) {
        SingularAttribute field = (SingularAttribute) obj;
        if (field.isId()) {
          if (i > 0) {
            jpql += " AND ";
          }
          jpql += "" + field.getName() + " = :p" + i;
          params[i] = Var.valueOf("p" + i, relationKeys[i].getObject(field.getType().getJavaType()));
          i++;
        }
      }
    }

    execute(jpql, params);
  }

  public Object insertRelation(String refId, Map<?, ?> data, Var... primaryKeys) {
    EntityMetadata metadata = getMetadata();
    RelationMetadata relationMetadata = metadata.getRelations().get(refId);

    EntityManager em = TransactionManager.getEntityManager(domainClass);

    filter(null, new PageRequest(0, 100), primaryKeys);
    Object insertion = null;
    Object result = null;
    if (relationMetadata.getAssossiationName() != null) {
      insertion = this.newInstance(relationMetadata.getAssossiationName());
      updateField(insertion, relationMetadata.getAttribute().getName(), Var.valueOf(data).getObject(forName(relationMetadata.getName())));
      updateField(insertion, relationMetadata.getAssociationAttribute().getName(), getObject());
      result = getObject();
    } else {
      insertion = Var.valueOf(data).getObject(forName(relationMetadata.getName()));
      updateField(insertion, relationMetadata.getAttribute().getName(), getObject());
      result = insertion;
    }

    if (!em.getTransaction().isActive()) {
      em.getTransaction().begin();
    }

    em.persist(insertion);
    return result;
  }

  public void filterByRelation(String refId, PageRequest pageRequest, Var... primaryKeys) {
    EntityMetadata metadata = getMetadata();
    RelationMetadata relationMetadata = metadata.getRelations().get(refId);

    EntityManager em = TransactionManager.getEntityManager(domainClass);

    EntityType type = null;
    String name = null;
    String selectAttr = "";
    String filterAttr = relationMetadata.getAttribute().getName();
    type = em.getMetamodel().entity(domainClass);

    if (relationMetadata.getAssossiationName() != null) {
      name = relationMetadata.getAssossiationName();
      selectAttr = "."+relationMetadata.getAttribute().getName();
      filterAttr = relationMetadata.getAssociationAttribute().getName();
    } else {
      name = relationMetadata.getName();
    }

    int i = 0;
    String jpql = "Select e"+selectAttr+" from "+name+" e where ";
    for (Object obj: type.getAttributes()) {
      SingularAttribute field = (SingularAttribute) obj;
      if (field.isId()) {
        if (i > 0) {
          jpql += " and ";
        }
        jpql += "e."+filterAttr+"."+field.getName()+" = :p"+i;
        primaryKeys[i].setId("p"+i);
      }
    }

    filter(jpql, pageRequest, primaryKeys);

  }

	/**
	 * Clean Datasource and to free up allocated memory
	 */
	public void clear() {
		this.pageRequest = new PageRequest(0, 100);
		this.current = -1;
		this.page = null;
	}

	/**
	 * Execute Query
	 *
	 * @param query - JPQL instruction for filter objects to remove
	 * @param params - Bidimentional array with params name and params value
	 */
	public void execute(String query, Var... params) {
		try {

			EntityManager em = TransactionManager.getEntityManager(domainClass);
			TypedQuery<?> strQuery = em.createQuery(query, domainClass);

			for (Var p : params) {
        strQuery.setParameter(p.getId(), p.getObject());
			}

			try {
				if (!em.getTransaction().isActive()) {
					em.getTransaction().begin();
				}
				strQuery.executeUpdate();
			} catch (Exception e) {
				throw new RuntimeException(e);
			}

		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	public Var getTotalElements() {
	  return new Var(this.page.getTotalElements());
	}

  @Override
  public String toString() {
	  if (this.page != null) {
	    return this.page.getContent().toString();
    } else {
	    return "[]";
    }
  }

  @Override
  public void serialize(JsonGenerator gen, SerializerProvider serializers) throws IOException {
    gen.writeObject(this.page.getContent());
  }

  @Override
  public void serializeWithType(JsonGenerator gen, SerializerProvider serializers, TypeSerializer typeSer) throws IOException {
    gen.writeObject(this.page.getContent());
  }
}
