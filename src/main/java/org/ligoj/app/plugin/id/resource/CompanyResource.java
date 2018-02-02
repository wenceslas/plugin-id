package org.ligoj.app.plugin.id.resource;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.transaction.Transactional;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriInfo;

import org.apache.commons.lang3.ObjectUtils;
import org.ligoj.app.iam.CompanyOrg;
import org.ligoj.app.iam.ContainerOrg;
import org.ligoj.app.iam.ICompanyRepository;
import org.ligoj.app.iam.UserOrg;
import org.ligoj.app.iam.dao.CacheCompanyRepository;
import org.ligoj.app.iam.model.CacheCompany;
import org.ligoj.app.model.ContainerType;
import org.ligoj.app.plugin.id.DnUtils;
import org.ligoj.app.plugin.id.model.ContainerScope;
import org.ligoj.bootstrap.core.json.TableItem;
import org.ligoj.bootstrap.core.json.datatable.DataTableAttributes;
import org.ligoj.bootstrap.core.validation.ValidationJsonException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * LDAP resource for companies.
 */
@Path(IdentityResource.SERVICE_URL + "/company")
@Service
@Produces(MediaType.APPLICATION_JSON)
@Transactional
public class CompanyResource extends AbstractContainerResource<CompanyOrg, ContainerEditionVo, CacheCompany> {

	/**
	 * Attribute name used as filter and path.
	 */
	public static final String COMPANY_ATTRIBUTE = "company";

	@Autowired
	private CacheCompanyRepository cacheCompanyRepository;

	/**
	 * Default constructor specifying the type as {@link ContainerType#COMPANY}
	 */
	protected CompanyResource() {
		super(ContainerType.COMPANY);
	}

	@Override
	public CacheCompanyRepository getCacheRepository() {
		return cacheCompanyRepository;
	}

	@Override
	public ICompanyRepository getRepository() {
		return getCompany();
	}

	/**
	 * Return the company name of current user.
	 * 
	 * @return The company name of current user or <code>null</code> if the current user is not in LDAP.
	 */
	public CompanyOrg getUserCompany() {
		final UserOrg user = getUser().findById(securityHelper.getLogin());
		if (user == null) {
			return null;
		}
		return getRepository().findById(ObjectUtils.defaultIfNull(user.getCompany(), ""));
	}

	/**
	 * Return the company DN of current user.
	 * 
	 * @return the company DN of current user or <code>null</code> if the current user is not in LDAP.
	 */
	private String getUserCompanyDn() {
		final CompanyOrg company = getUserCompany();
		if (company == null) {
			return null;
		}
		return company.getDn();
	}

	/**
	 * Indicates the current user is inside the internal scope of people.
	 * 
	 * @return <code>true</code> when the current user is inside the internal scope of people.
	 */
	public boolean isUserInternalCommpany() {
		return ObjectUtils.defaultIfNull(getUserCompanyDn(), "").endsWith(ObjectUtils.defaultIfNull(getUser().getPeopleInternalBaseDn(), ""));
	}

	/**
	 * Return groups matching to given criteria. The visible groups, trees and companies are checked. The returned
	 * groups of each user depends on the groups the user can see/write in CN form.
	 * 
	 * @param uriInfo
	 *            filter data.
	 * @return found groups.
	 */
	@GET
	public TableItem<ContainerCountVo> findAll(@Context final UriInfo uriInfo) {
		final PageRequest pageRequest = paginationJson.getPageRequest(uriInfo, ORDERED_COLUMNS);

		final List<ContainerScope> types = containerScopeResource.findAllDescOrder(ContainerType.COMPANY);
		final Set<CompanyOrg> visibleCompanies = getContainers();
		final Set<String> visibleCompaniesAsString = visibleCompanies.stream().map(CompanyOrg::getId).collect(Collectors.toSet());
		final Set<CompanyOrg> writeCompanies = getContainersForWrite();
		final Set<CompanyOrg> adminCompanies = getContainersForAdmin();
		final Map<String, UserOrg> users = getUser().findAll();

		// Search the companies
		final Page<CompanyOrg> findAll = getRepository().findAll(visibleCompanies, DataTableAttributes.getSearch(uriInfo), pageRequest,
				Collections.singletonMap(TYPE_ATTRIBUTE, new TypeComparator(types)));

		// Apply pagination and secure the users data
		return paginationJson.applyPagination(uriInfo, findAll, rawCompanyLdap -> {
			// Build the secured company with counter
			final ContainerCountVo securedUser = newContainerCountVo(rawCompanyLdap, writeCompanies, adminCompanies, types);

			// Computed the total members, unrestricted visibility
			securedUser.setCount((int) users.values().stream().filter(user -> rawCompanyLdap.getId().equals(user.getCompany())).count());

			// Computed the visible members : same company and visible company
			securedUser.setCountVisible((int) users.values().stream().filter(user -> rawCompanyLdap.getId().equals(user.getCompany()))
					.filter(user -> visibleCompaniesAsString.contains(user.getCompany())).count());
			return securedUser;
		});
	}

	@Override
	protected void checkForDeletion(final ContainerOrg container) {
		super.checkForDeletion(container);

		// Company deletion is only possible where there is no user inside this company, or inside any sub-company
		final Map<String, UserOrg> users = getUser().findAll();
		if (getRepository().findAll().values().stream().filter(c -> DnUtils.equalsOrParentOf(container.getDn(), c.getDn()))
				.anyMatch(c -> users.values().stream().map(UserOrg::getCompany).anyMatch(c.getId()::equals))) {
			// Locked container is inside the container to delete
			throw new ValidationJsonException(getTypeName(), "not-empty-company", "0", getTypeName(), "1", container.getId());
		}
	}

	@Override
	protected String toDn(final ContainerEditionVo container, final ContainerScope scope) {
		return "ou=" + container.getName() + "," + scope.getDn();
	}

}